import SparkMD5 from 'spark-md5'
import { uploadApi } from '../api'

const STORE_KEY = 'liteark_uploads_v1'

export function uid() {
  return Date.now().toString(36) + Math.random().toString(36).slice(2, 8)
}

export function readSaved() {
  try {
    const arr = JSON.parse(localStorage.getItem(STORE_KEY) || '[]')
    return Array.isArray(arr) ? arr : []
  } catch (e) { return [] }
}

export function saveTask(task) {
  if (!task || task.status === 'done' || task.status === 'cancelled') return
  const all = readSaved().filter((t) => t.id !== task.id)
  all.push({
    id: task.id, name: task.name, size: task.size, md5: task.md5,
    uploadId: task.uploadId, uploaded: task.uploaded, chunkSize: task.chunkSize,
    chunkTotal: task.chunkTotal, parentId: task.parentId, clientType: task.clientType,
    status: task.status, savedAt: Date.now(),
  })
  localStorage.setItem(STORE_KEY, JSON.stringify(all.slice(-30)))
}

export function dropTask(id) {
  localStorage.setItem(STORE_KEY, JSON.stringify(readSaved().filter((t) => t.id !== id)))
}

/** 全量 MD5：分片读取，避免大文件一次性载入内存 */
export function calculateMd5(file, onProgress, pieceSize = 4 * 1024 * 1024, readTimeout = 120000) {
  return new Promise((resolve, reject) => {
    const spark = new SparkMD5.ArrayBuffer()
    const reader = new FileReader()
    let offset = 0
    let settled = false
    const fail = (msg) => { if (!settled) { settled = true; reject(new Error(msg)) } }
    const next = () => {
      if (settled) return
      if (offset >= file.size) {
        if (settled) return
        settled = true
        return resolve(spark.end())
      }
      const end = Math.min(offset + pieceSize, file.size)
      // 看门狗：文件在云盘/外置卷上未落盘时，FileReader 可能既不 load 也不 error，会静默卡死
      const timer = setTimeout(() => fail('读取文件超时，请确认文件已下载到本机后再上传'), readTimeout)
      reader.onload = (e) => {
        clearTimeout(timer)
        try {
          spark.append(e.target.result)
        } catch (err) {
          // 回调里抛出的异常不会让 Promise 结算，不显式 reject 会永久停在 0%
          return fail('文件校验值计算失败')
        }
        offset = end
        if (onProgress) {
          try { onProgress(Math.round((offset / file.size) * 100)) } catch (err) { /* 进度回调异常不应中断校验 */ }
        }
        next()
      }
      reader.onerror = () => { clearTimeout(timer); fail('文件校验值计算失败，请确认文件可读') }
      reader.onabort = () => { clearTimeout(timer); fail('文件读取被中断') }
      try {
        reader.readAsArrayBuffer(file.slice(offset, end))
      } catch (err) {
        clearTimeout(timer)
        fail('文件读取失败：' + (err && err.message ? err.message : '未知错误'))
      }
    }
    next()
  })
}

function sleep(ms) { return new Promise((r) => setTimeout(r, ms)) }

/**
 * 分片上传执行器：MD5 → init（断点复用）→ 并发分片 → complete。
 * 支持暂停/继续/取消，单分片超时 30s、失败重试 3 次，进度写入 localStorage。
 */
export class UploadRunner {
  constructor(file, opts = {}) {
    this.id = opts.id || uid()
    this.file = file
    this.name = file.name
    this.size = file.size
    this.parentId = opts.parentId || 0
    this.clientType = opts.clientType || 'PC'
    this.chunkSize = opts.chunkSize || 2 * 1024 * 1024
    this.concurrency = Math.max(1, opts.concurrency || 2)
    this.md5 = opts.md5 || ''
    this.uploadId = opts.uploadId || ''
    this.uploaded = opts.uploaded || []
    this.chunkTotal = 0
    this.progress = 0
    this.status = 'waiting'
    this.statusText = '排队中'
    this.paused = false
    this.cancelled = false
    this.started = false
    this.deferred = false
    this.inflight = new Map()   // 分片 index -> 已发出字节数（用于平滑进度）
    this._lastEmit = 0
    this.speed = 0
    this.onChange = opts.onChange || (() => {})
    this.onDone = opts.onDone || (() => {})
  }

  setStatus(status, text) {
    this.status = status
    this.statusText = text
    this.onChange(this)
  }

  pause() {
    this.paused = true
    // 还在排队（没拿到并发位）的任务，暂停即让位：不能让一个暂停的文件占住名额
    if (!this.started) this.deferred = true
    this.setStatus('paused', '已暂停'); saveTask(this)
  }

  resume() {
    if (this.cancelled) return
    this.paused = false
    if (this.deferred && !this.started) { this.deferred = false; this.start(); return }
    this.setStatus('uploading', '继续上传'); this.uploadChunks()
  }

  cancel() {
    this.cancelled = true
    this.paused = false
    if (this.uploadId) uploadApi.cancel(this.uploadId).catch(() => {})
    dropTask(this.id)
    this.setStatus('cancelled', '已取消')
    this.onDone(this, true)
  }

  async start() {
    if (this.started || this.cancelled) return
    // 排队期间被暂停：先不占用并发位，等用户点“继续”再自己开跑
    if (this.paused) { this.deferred = true; this.setStatus('paused', '已暂停'); return }
    this.started = true
    try {
      if (!this.md5) {
        this.setStatus('hashing', '计算文件校验值…')
        this.md5 = await calculateMd5(this.file, (p) => {
          this.statusText = `计算校验值 ${p}%`
          this.onChange(this)
        })
      }
      // 页面刷新后重新选择同一文件：匹配本地记录，复用服务端任务
      const saved = readSaved().find((t) => t.md5 === this.md5 && t.name === this.name && t.size === this.size)
      if (saved && saved.uploadId) {
        this.uploadId = saved.uploadId
        this.uploaded = saved.uploaded || []
      }
      this.setStatus('init', '准备上传…')
      const r = await uploadApi.init({
        md5: this.md5, name: this.name, totalSize: this.size,
        chunkSize: this.chunkSize, chunkTotal: Math.ceil(this.size / this.chunkSize),
        parentId: this.parentId,
      })
      this.uploadId = r.data.uploadId
      this.uploaded = (r.data.uploaded || []).map(Number)
      this.chunkTotal = Math.ceil(this.size / this.chunkSize)
      saveTask(this)
      this.setStatus('uploading', '上传中')
      await this.uploadChunks()
      // 暂停/取消时让出并发位，交给 resume() 从剩余分片继续
      if (this.cancelled) return
      if (this.paused) { this.setStatus('paused', '已暂停'); return }
      const done = await uploadApi.complete(this.uploadId)
      dropTask(this.id)
      this.progress = 100
      this.setStatus('done', done.data.skipped ? '已跳过（同名）' : '完成')
      this.onDone(this, false, done.data)
    } catch (e) {
      this.setStatus('error', '失败: ' + (e.message || '未知错误'))
      saveTask(this)
      this.onDone(this, true)
    }
  }

  async uploadChunks() {
    if (this._running) return
    this._running = true
    this.inflight.clear()
    const pending = []
    for (let i = 0; i < this.chunkTotal; i++) if (!this.uploaded.includes(i)) pending.push(i)
    let cursor = 0
    const worker = async () => {
      while (cursor < pending.length) {
        // 暂停时整组 worker 退出，把并发位让给队列里其他文件；恢复时由 resume() 重新起
        if (this.cancelled || this.paused) return
        const index = pending[cursor++]
        const ok = await this.uploadOne(index)
        if (!ok) return
        this.inflight.delete(index)
        this.uploaded.push(index)
        this.uploaded.sort((a, b) => a - b)
        this.updateProgress(true)
        saveTask(this)
      }
    }
    await Promise.all(Array.from({ length: Math.min(this.concurrency, pending.length || 1) }, worker))
    this._running = false
  }

  /** 单个分片的字节数（最后一片可能不足 chunkSize） */
  chunkBytes(index) {
    return Math.min(this.chunkSize, this.size - index * this.chunkSize)
  }

  /**
   * 按字节汇总进度：已完成分片 + 在途分片已发出的字节。
   * 分片较大时只看「已完成片数」会让进度条长时间停在同一格，并行上传时更明显。
   */
  updateProgress(force = false) {
    if (!this.size || !this.chunkTotal) return
    let done = 0
    for (const i of this.uploaded) done += this.chunkBytes(i)
    this.inflight.forEach((loaded, i) => { done += Math.min(loaded, this.chunkBytes(i)) })
    const pct = Math.min(99, Math.max(0, Math.floor((done * 100) / this.size)))
    if (pct === this.progress && !force) return
    const now = Date.now()
    if (!force && now - (this._lastEmit || 0) < 120) return
    this._lastEmit = now
    this.progress = pct
    this.statusText = `上传中 ${pct}%`
    this.onChange(this)
  }

  async uploadOne(index) {
    const start = index * this.chunkSize
    const blob = this.file.slice(start, Math.min(start + this.chunkSize, this.size))
    const full = this.chunkBytes(index)
    for (let attempt = 1; attempt <= 3; attempt++) {
      if (this.cancelled) return false
      this.inflight.set(index, 0)
      try {
        const fd = new FormData()
        fd.append('uploadId', this.uploadId)
        fd.append('index', String(index))
        fd.append('chunk', blob, `chunk-${index}`)
        await uploadApi.chunk(fd, (p) => {
          this.inflight.set(index, Math.round((full * p) / 100))
          this.updateProgress()
        })
        this.inflight.set(index, full)
        return true
      } catch (e) {
        this.inflight.delete(index)
        if (attempt === 3) { this.setStatus('error', `分片 ${index} 上传失败`); return false }
        await sleep(500 * attempt)
      }
    }
    return false
  }
}
