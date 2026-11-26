<template>
  <div class="disk-page">
    <div class="disk-main">
      <!-- 目录树（仅桌面） -->
      <aside v-if="!isMobile && !isTablet" class="tree-panel">
        <div class="tree-title">目录</div>
        <el-tree :data="treeData" node-key="id" :props="{ label: 'label', children: 'children' }"
          :default-expanded-keys="[0]" :expand-on-click-node="false" highlight-current @node-click="onTreeNode">
          <template #default="{ node, data }">
            <span class="tree-node">
              <el-icon v-if="data.id === 0"><HomeFilled /></el-icon><el-icon v-else><Folder /></el-icon>
              <span>{{ node.label }}</span>
            </span>
          </template>
        </el-tree>
      </aside>

      <section class="file-panel">
        <!-- 面包屑 + 搜索 -->
        <div class="toolbar">
          <div class="toolbar-left">
            <el-button v-if="isMobile" :icon="ArrowLeft" text @click="goUp" />
            <el-breadcrumb separator="/" class="crumb">
              <el-breadcrumb-item v-for="(c, i) in breadcrumbs" :key="c.id">
                <a href="#" @click.prevent="goFolder(c.id, i)">{{ c.name }}</a>
              </el-breadcrumb-item>
            </el-breadcrumb>
          </div>
          <div class="toolbar-right">
            <el-input v-model="keyword" placeholder="搜索" size="small" clearable class="search-input"
              :prefix-icon="Search" @input="debouncedLoad" />
            <el-button-group v-if="!isMobile">
              <el-button size="small" :class="{ 'is-active': viewMode === 'list' }" :icon="List" @click="viewMode = 'list'" />
              <el-button size="small" :class="{ 'is-active': viewMode === 'grid' }" :icon="Grid" @click="viewMode = 'grid'" />
            </el-button-group>
          </div>
        </div>

        <!-- 操作栏（桌面常驻 / 移动多选模式） -->
        <div class="action-bar" :class="{ 'multi-mode': multiSelect }">
          <template v-if="!isMobile || multiSelect">
            <el-button v-if="multiSelect" size="small" @click="exitMulti">取消</el-button>
            <el-button v-if="!isMobile && canUpload" type="primary" size="small" :icon="Plus" @click="openCreateFolder">新建文件夹</el-button>
            <el-button v-if="!isMobile && canUpload" type="primary" size="small" :icon="UploadFilled" @click="pickFiles">上传文件</el-button>
            <el-button v-if="!isMobile && canUpload" type="primary" size="small" plain :icon="FolderAdd" @click="pickFolder">上传文件夹</el-button>
            <el-button v-if="isMobile && canUpload" size="small" :icon="FolderAdd" @click="openCreateFolder">新建</el-button>
            <el-divider v-if="!isMobile" direction="vertical" />
            <el-button size="small" :icon="Download" :disabled="!allHave('download')" @click="downloadSelected">下载</el-button>
            <el-button size="small" :icon="DocumentCopy" :disabled="!allHave('view') || !canUpload" @click="setClipboard('copy')">复制</el-button>
            <el-button size="small" :icon="Scissor" :disabled="!allHave('edit') || !canUpload" @click="setClipboard('move')">剪切</el-button>
            <el-button size="small" :icon="FolderOpened" :disabled="!clipboard || !canUpload" @click="pasteClipboard">粘贴</el-button>
            <el-button size="small" :icon="Share" :disabled="selected.length !== 1 || !!selected[0]?.granted || !canShare(selected[0])" @click="openShare">分享</el-button>
            <el-button v-if="canGrantHere" size="small" :icon="Key" @click="openGrant()">授权</el-button>
            <el-button size="small" type="danger" :icon="Delete" :disabled="!allHave('edit')" @click="deleteSelected">删除</el-button>
            <span class="spacer" />
            <el-button v-if="!isMobile" size="small" @click="multiSelect = true">多选</el-button>
            <span v-if="selected.length" class="selected-tip">已选 {{ selected.length }} 项</span>
          </template>
          <template v-else>
            <el-button v-if="canUpload" size="small" :icon="UploadFilled" @click="pickFiles">上传</el-button>
            <el-button v-if="canGrantHere" size="small" :icon="Key" @click="openGrant()">授权</el-button>
            <span class="spacer" />
            <el-button size="small" @click="multiSelect = true">多选</el-button>
            <el-button size="small" :icon="Refresh" @click="loadAll" />
          </template>
        </div>

        <!-- 共享目录横幅 -->
        <div v-if="currentFolder.granted" class="grant-banner">
          <el-icon><Share /></el-icon>
          <span>当前位于 <b>{{ currentFolder.ownerName }}</b> 共享的文件夹，你的权限：</span>
          <el-tag v-for="p in currentPerms" :key="p" size="small" type="success" effect="plain">{{ labelOfPerm(p) }}</el-tag>
          <span v-if="!currentPerms.length" class="muted-tip">无</span>
        </div>

        <!-- 文件区 -->
        <div class="file-area" v-loading="loading" :class="{ 'drop-active': dropActive }"
          @dragover.prevent="onDragOver" @dragenter.prevent="onDragOver" @dragleave.prevent="onDragLeave" @drop.prevent="onDrop">
          <!-- 列表（桌面/平板） -->
          <el-table v-if="viewMode === 'list'" ref="tableRef" :data="files" height="100%" size="small"
            @row-dblclick="onDblClick" @selection-change="onSelChange" :row-class-name="rowClass">
            <el-table-column type="selection" width="44" />
            <el-table-column label="文件名" min-width="240">
              <template #default="{ row }">
                <div class="name-cell" @click="onRowClick(row, $event)">
                  <el-icon :size="20" :color="iconColor(row)"><component :is="iconOf(row)" /></el-icon>
                  <span :title="row.name">{{ row.name }}</span>
                  <el-tag v-if="row.granted && currentId === 0" size="small" type="info" effect="plain" class="shared-tag">共享</el-tag>
                </div>
              </template>
            </el-table-column>
            <el-table-column label="大小" width="110">
              <template #default="{ row }">{{ row.isDir ? '—' : size(row.size) }}</template>
            </el-table-column>
            <el-table-column prop="updatedAt" label="修改时间" width="165" v-if="!isMobile" />
            <el-table-column label="操作" width="380" fixed="right">
              <template #default="{ row }">
                <el-button v-if="has(row, 'download')" link type="primary" size="small" @click.stop="downloadItem(row)">下载</el-button>
                <el-button v-if="has(row, 'edit')" link type="primary" size="small" @click.stop="openRename(row)">重命名</el-button>
                <el-button v-if="row.isDir && !row.granted && has(row, 'edit')" link type="primary" size="small" @click.stop="openGrant(row)">授权</el-button>
                <el-button v-if="!row.granted && canShare(row)" link type="primary" size="small" @click.stop="openShareOne(row)">分享</el-button>
                <el-button v-if="canEdit(row) && has(row, 'edit')" link type="primary" size="small" @click.stop="openEditor(row)">编辑</el-button>
                <el-button link type="primary" size="small" v-if="!row.isDir" @click.stop="preview(row)">预览</el-button>
                <el-button v-if="has(row, 'edit')" link type="danger" size="small" @click.stop="deleteItem(row)">删除</el-button>
              </template>
            </el-table-column>
            <template #empty><el-empty description="此目录为空" /></template>
          </el-table>

          <!-- 网格（移动默认 / 桌面可切） -->
          <div v-else class="grid-view">
            <div v-for="f in files" :key="f.id" class="grid-item" :class="{ selected: selectedIds.includes(f.id) }"
              @click="onGridClick(f)" @dblclick="onDblClick(f)">
              <div class="grid-thumb">
                <el-image v-if="isImage(f)" :src="fileApi.thumbnailUrl(f.id, isMobile ? 200 : 400)" fit="cover" class="thumb" @click.stop />
                <el-icon v-else :size="46" :color="iconColor(f)"><component :is="iconOf(f)" /></el-icon>
                <div v-if="multiSelect" class="check-mark" :class="{ on: selectedIds.includes(f.id) }">
                  <el-icon v-if="selectedIds.includes(f.id)"><Check /></el-icon>
                </div>
              </div>
              <div class="grid-name" :title="f.name">{{ f.name }}</div>
              <div class="grid-meta">{{ f.isDir ? '文件夹' : size(f.size) }}</div>
              <el-button v-if="!f.isDir" class="grid-more" :icon="MoreFilled" text @click.stop="openActions(f)" />
            </div>
            <el-empty v-if="!files.length" description="此目录为空" />
          </div>
        </div>
      </section>
    </div>

    <!-- 移动 FAB -->
    <el-button v-if="isMobile && !multiSelect" class="fab" type="primary" :icon="Plus" circle @click="openFab" />

    <!-- FAB 底部动作面板 -->
    <el-dialog v-model="fabVisible" title="上传文件" width="100%" class="bottom-sheet" align-center>
      <div class="fab-actions">
        <el-button type="primary" :icon="Plus" @click="pickFiles">从相册选择 / 选择文件</el-button>
        <el-button :icon="Camera" @click="clickCamera">拍照上传</el-button>
        <el-button :icon="FolderAdd" @click="openCreateFolder">新建文件夹</el-button>
      </div>
      <p class="fab-tip">移动端不支持文件夹批量上传与拖拽，请在电脑端使用。切后台或断网后可在上传队列继续。</p>
    </el-dialog>

    <!-- 单项操作面板 -->
    <el-dialog v-model="actionsVisible" :title="actionTarget?.name || '操作'" width="100%" class="bottom-sheet" align-center>
      <div class="fab-actions vertical">
        <el-button v-if="has(actionTarget, 'edit')" @click="doAction('rename')">重命名</el-button>
        <el-button v-if="!actionTarget?.isDir" @click="doAction('preview')">详情 / 预览</el-button>
        <el-button v-if="!actionTarget?.granted && canShare(actionTarget)" @click="doAction('share')">分享</el-button>
        <el-button v-if="actionTarget?.isDir && !actionTarget?.granted && has(actionTarget, 'edit')" @click="doAction('grant')">授权给同事</el-button>
        <el-button v-if="has(actionTarget, 'download')" @click="doAction('download')">下载</el-button>
        <el-button v-if="has(actionTarget, 'edit')" type="danger" @click="doAction('delete')">删除</el-button>
      </div>
    </el-dialog>

    <!-- 上传队列 -->
    <el-drawer v-model="uploadDrawer" title="上传队列" size="460px">
      <div v-if="!queue.length" class="empty-tip">暂无上传任务</div>
      <div v-for="t in queue" :key="t.id" class="upload-item">
        <div class="upload-head"><span>{{ t.name }}</span><span>{{ t.statusText }}</span></div>
        <el-progress :percentage="t.progress" :status="t.progress === 100 ? 'success' : undefined" />
        <div class="upload-foot">
          <span>{{ size(t.size) }} · {{ t.clientType || '' }}</span>
          <span>
            <el-button v-if="t.status === 'uploading' || t.status === 'waiting'" link size="small" @click="pauseTask(t)">暂停</el-button>
            <el-button v-if="t.status === 'paused'" link size="small" @click="t.needsFile ? pickFiles() : resumeTask(t)">继续</el-button>
            <el-button v-if="['uploading','paused','hashing','init','error','waiting'].includes(t.status)" link type="danger" size="small" @click="cancelTask(t)">取消</el-button>
          </span>
        </div>
      </div>
    </el-drawer>

    <!-- 对话框：新建/重命名/分享/编辑 -->
    <el-dialog v-model="createVisible" title="新建文件夹" width="420px">
      <el-input v-model="newName" placeholder="文件夹名称" @keyup.enter="doCreate" />
      <template #footer><el-button @click="createVisible = false">取消</el-button><el-button type="primary" @click="doCreate">创建</el-button></template>
    </el-dialog>

    <el-dialog v-model="renameVisible" title="重命名" width="420px">
      <el-input v-model="renameName" @keyup.enter="doRename" />
      <template #footer><el-button @click="renameVisible = false">取消</el-button><el-button type="primary" @click="doRename">确定</el-button></template>
    </el-dialog>

    <!-- 对话框：文件夹授权（普通用户自助共享给同事） -->
    <el-dialog v-model="grantVisible" :title="'授权 · ' + (grantData.folderName || grantTarget?.name || '')" width="640px" top="6vh" style="max-width:94vw">
      <div class="grant-note">
        <el-icon><Key /></el-icon>
        <span>勾选的权限对<b>该文件夹及其全部下级</b>生效，后续新增的文件与文件夹自动沿用；可随时撤销。</span>
      </div>
      <div v-if="grantData.path" class="grant-path">位置：我的文件 / {{ grantData.path }}</div>

      <el-form label-position="top" class="grant-form">
        <el-form-item label="授权给">
          <el-select v-model="grantForm.userId" filterable placeholder="选择同事" style="width:100%" @change="onGrantUser">
            <el-option v-for="u in grantData.candidates" :key="u.id" :value="u.id"
              :label="u.name + (u.name !== u.username ? '（' + u.username + '）' : '') + (u.roleName ? ' · ' + u.roleName : '') + (u.perms && u.perms.length ? ' · 已授权' : '')" />
          </el-select>
          <div v-if="!grantData.candidates.length" class="grant-hint">暂无可授权的其他普通用户</div>
        </el-form-item>

        <el-form-item label="授权方式">
          <el-radio-group v-model="grantForm.mode" @change="onGrantMode">
            <el-radio-button value="direct">直接指定权限</el-radio-button>
            <el-radio-button value="role">按角色授权</el-radio-button>
          </el-radio-group>
          <span class="grant-hint" style="margin-left:10px">按角色授权时，该同事的权限跟随角色默认配置自动调整</span>
        </el-form-item>

        <el-form-item v-if="grantForm.mode === 'role'" label="角色模板">
          <el-select v-model="grantForm.roleId" filterable placeholder="选择角色" style="width:100%" @change="onGrantRole">
            <el-option v-for="r in grantData.roles" :key="r.id" :value="r.id"
              :label="r.name + '（' + (r.userCount || 0) + ' 人）' + (r.perms && r.perms.length ? ' · ' + r.perms.map(labelOfPerm).join('/') : ' · 此目录无默认权限')" />
          </el-select>
          <div v-if="selectedRole" class="grant-role-note">
            角色「{{ selectedRole.name }}」在当前文件夹的默认权限：
            <span v-if="selectedRole.perms && selectedRole.perms.length">{{ selectedRole.perms.map(labelOfPerm).join(' / ') }}</span>
            <span v-else class="muted-tip">无（该角色尚未在此目录配置默认权限）</span>
            <div class="grant-hint">实际授权 = 角色默认权限 ∩ 你在本文件夹的可授出范围；下方勾选用于设定上限，可再收窄。</div>
          </div>
        </el-form-item>

        <el-form-item :label="grantForm.mode === 'role' ? '权限上限（角色默认权限的基础上可收窄）' : '权限'">
          <div class="grant-perms">
            <el-checkbox-group v-model="grantForm.perms">
              <el-checkbox v-for="p in PERM_DEFS" :key="p.value" :value="p.value"
                :disabled="!grantData.maxPerms?.includes(p.value)">{{ p.label }}</el-checkbox>
            </el-checkbox-group>
            <el-button-group>
              <el-button size="small" @click="grantPreset('readonly')">只读</el-button>
              <el-button size="small" @click="grantPreset('upload')">可上传</el-button>
              <el-button size="small" @click="grantPreset('all')">全部</el-button>
            </el-button-group>
          </div>
          <div class="grant-hint">{{ grantForm.mode === 'role'
            ? '实际生效：' + (grantEffectivePerms.length ? grantEffectivePerms.map(labelOfPerm).join(' / ') : '无（角色在此目录没有默认权限，或已被上限收窄为 0）')
            : (grantForm.perms.length ? grantForm.perms.map(labelOfPerm).join(' / ') : '未勾选 = 该同事看不到这个文件夹') }}</div>
        </el-form-item>

        <el-form-item label="继承上级权限">
          <el-switch v-model="grantForm.inherit" />
          <span class="grant-hint" style="margin-left:10px">开启时受上级规则约束；关闭后该文件夹成为新的权限边界</span>
        </el-form-item>

        <el-button type="primary" :disabled="!grantForm.userId" @click="saveGrant">保存授权</el-button>
      </el-form>

      <div class="grant-rules-title">已授权（含下级目录）</div>
      <el-table :data="grantData.rules" size="small" border max-height="240">
        <el-table-column prop="subjectName" label="授权对象" width="120" show-overflow-tooltip />
        <el-table-column v-if="!isMobile" label="来源" width="120" show-overflow-tooltip>
          <template #default="{ row }">
            <span v-if="row.sourceRoleName" class="muted-tip">角色 · {{ row.sourceRoleName }}</span>
            <span v-else class="muted-tip">直接指定</span>
          </template>
        </el-table-column>
        <el-table-column v-if="!isMobile" prop="folderPath" label="作用范围" min-width="150" show-overflow-tooltip />
        <el-table-column label="权限" min-width="150">
          <template #default="{ row }">
            <el-tag v-for="p in row.perms" :key="p" size="small" type="success" effect="plain" class="perm-tag">{{ labelOfPerm(p) }}</el-tag>
            <span v-if="!row.perms?.length" class="muted-tip">不可见</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="90">
          <template #default="{ row }">
            <el-button v-if="row.here && row.subjectType === 'user'" link type="danger" size="small" @click="revokeGrant(row)">撤销</el-button>
            <span v-else-if="row.subjectType === 'role'" class="muted-tip">角色默认</span>
            <span v-else class="muted-tip">下级</span>
          </template>
        </el-table-column>
        <template #empty><el-empty description="这个文件夹还没有授权给任何人" :image-size="60" /></template>
      </el-table>

      <template #footer><el-button @click="grantVisible = false">关闭</el-button></template>
    </el-dialog>

    <el-dialog v-model="shareVisible" :title="shareForm.shareType === 'internal' ? '内部分享' : '创建共享链接'" width="520px">
      <div class="share-file">{{ shareTarget?.name }}</div>
      <el-form label-position="top">
        <el-form-item label="分享范围">
          <el-radio-group v-model="shareForm.shareType">
            <el-radio-button value="external" :disabled="!canShareType('external')">外部分享</el-radio-button>
            <el-radio-button value="internal" :disabled="!canShareType('internal')">内部分享</el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-form-item v-if="shareForm.shareType === 'internal'" label="分享给">
          <el-select v-model="shareForm.targetIds" multiple filterable placeholder="选择内部用户" style="width:100%">
            <el-option v-for="u in shareUsers" :key="u.id" :value="u.id" :label="u.name + '（' + u.username + '）'" />
          </el-select>
          <div class="grant-hint">仅名单内用户登录后可访问，无需访问密码；文件与文件夹都支持。</div>
        </el-form-item>
        <el-form-item label="访问权限">
          <el-radio-group v-model="shareForm.permission">
            <el-radio-button value="view">仅查看</el-radio-button>
            <el-radio-button value="download">可下载</el-radio-button>
            <el-radio-button value="edit">可编辑</el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-form-item v-if="shareForm.shareType === 'external'" label="访问密码"><el-input v-model="shareForm.password" placeholder="选填" /></el-form-item>
        <el-form-item label="有效期">
          <el-select v-model="shareForm.expireDays" style="width:100%">
            <el-option :value="1" label="1 天" /><el-option :value="7" label="7 天" />
            <el-option :value="30" label="30 天" /><el-option :value="0" label="永久" />
          </el-select>
        </el-form-item>
        <el-form-item label="下载次数限制（0 为不限）">
          <el-input-number v-model="shareForm.maxDownloads" :min="0" :max="100000" style="width:100%" />
        </el-form-item>
      </el-form>
      <div v-if="shareUrl" class="share-result">
        <el-input :model-value="shareUrl" readonly />
        <el-button type="primary" size="small" @click="copyShare">复制</el-button>
      </div>
      <template #footer>
        <el-button @click="shareVisible = false">关闭</el-button>
        <el-button type="primary" @click="doShare">{{ shareForm.shareType === 'internal' ? '创建内部分享' : '生成链接' }}</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="editorVisible" :title="'编辑 · ' + (editorFile?.name || '')" width="760px" top="5vh" @close="onEditorClose">
      <el-input v-model="editorContent" type="textarea" :rows="20" class="editor-area" />
      <div class="editor-tip">关闭时自动保存新版本 · v{{ editorFile?.version }}</div>
      <template #footer>
        <el-button :icon="Clock" @click="openVersions(editorFile)">历史版本</el-button>
        <el-button @click="editorVisible = false">关闭</el-button>
        <el-button type="primary" @click="saveEditor">保存版本</el-button>
      </template>
    </el-dialog>

    <!-- 预览（默认查看模式）：图片/PDF/Markdown/CSV/Office 只读，文本类可点「编辑」切换 -->
    <el-dialog v-model="previewVisible" :title="'预览 · ' + (previewFile?.name || '')" width="880px" top="5vh">
      <div v-loading="previewLoading" class="preview-dialog">
        <el-image v-if="isImage(previewFile)" :src="previewUrl(previewFile.id)" fit="contain" class="preview-image" />
        <video v-else-if="isVideo(previewFile)" :src="previewUrl(previewFile.id)" class="preview-video" controls preload="metadata" />
        <audio v-else-if="isAudio(previewFile)" :src="previewUrl(previewFile.id)" class="preview-audio" controls preload="metadata" />
        <iframe v-else-if="previewFile?.ext === 'pdf'" :src="previewUrl(previewFile.id)" class="preview-frame" />
        <div v-else-if="previewFile?.ext === 'md'" class="md-body" v-html="mdHtml" />
        <div v-else-if="previewFile?.ext === 'csv'">
          <el-table :data="csvRows" height="55vh" border size="small">
            <el-table-column v-for="(h, i) in csvHeader" :key="i" :label="h" :prop="'c' + i" min-width="120" />
          </el-table>
          <p v-if="csvTruncated" class="editor-tip">仅显示前 200 行，完整内容请下载查看</p>
        </div>
        <iframe v-else-if="isOffice(previewFile) && officeSrc" :src="officeSrc" class="preview-frame" />
        <pre v-else class="preview-text">{{ previewText }}</pre>
        <div v-if="previewError" class="preview-error">{{ previewError }}
          <el-button link type="primary" @click="downloadOne(previewFile)">下载查看</el-button>
        </div>
        <div v-if="previewFile && (previewFile.ext === 'pdf' || isOffice(previewFile))" class="preview-actions">
          <el-button link type="primary" @click="openPreviewExternal">在新窗口打开</el-button>
        </div>
      </div>
      <template #footer>
        <el-button v-if="canEdit(previewFile) && has(previewFile, 'edit')" type="primary" :icon="EditPen"
          @click="switchToEdit">编辑</el-button>
        <el-button @click="previewVisible = false">关闭</el-button>
      </template>
    </el-dialog>

    <!-- 历史版本（移动端只读） -->
    <el-dialog v-model="versionsVisible" title="历史版本" width="600px">
      <el-table :data="versions" size="small" height="360">
        <el-table-column prop="version" label="版本" width="80" />
        <el-table-column label="大小" width="110"><template #default="{ row }">{{ size(row.size) }}</template></el-table-column>
        <el-table-column prop="createdAt" label="时间" />
        <el-table-column label="操作" width="90">
          <template #default="{ row }">
            <el-button v-if="!isMobile" link type="primary" size="small" @click="doRollback(row)">回滚</el-button>
          </template>
        </el-table-column>
      </el-table>
      <p v-if="isMobile" class="editor-tip">移动端仅可查看历史版本，回滚请在电脑端操作。</p>
    </el-dialog>

    <input id="native-file" type="file" multiple style="display:none" @change="onFiles" />
    <input id="native-folder" type="file" multiple webkitdirectory style="display:none" @change="onFiles" />
    <input id="native-file-mobile" type="file" multiple style="display:none" @change="onFiles" />
    <input id="native-camera" type="file" accept="image/*" capture="environment" style="display:none" @change="onFiles" />
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted, onUnmounted, markRaw } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  UploadFilled, Search, Refresh, List, Grid, Plus, Download, DocumentCopy, Scissor,
  FolderOpened, Share, Delete, Folder, HomeFilled, ArrowLeft, FolderAdd, Check, MoreFilled,
  Document, Picture, VideoCamera, Tickets, Camera, Clock, Headset, Key, EditPen,
} from '@element-plus/icons-vue'
import { fileApi, shareApi, uploadApi, grantApi } from '../api'
import { UploadRunner, readSaved, uid } from '../utils/uploader'
import { marked } from 'marked'
import DOMPurify from 'dompurify'
import { useRoute } from 'vue-router'

const route = useRoute()

const isMobile = ref(window.innerWidth < 767)
const isTablet = ref(window.innerWidth >= 768 && window.innerWidth < 1200)
function resize() {
  isMobile.value = window.innerWidth < 768
  isTablet.value = window.innerWidth >= 768 && window.innerWidth < 1200
  if (isMobile.value && viewMode.value === 'list') viewMode.value = 'grid'
}
onMounted(() => window.addEventListener('resize', resize))
onUnmounted(() => { window.removeEventListener('resize', resize); if (autoSaveTimer) clearInterval(autoSaveTimer) })

const loading = ref(false)
const files = ref([])
const treeData = ref([])
const breadcrumbs = ref([{ id: 0, name: '全部文件' }])
const currentId = ref(0)
const keyword = ref('')
const viewMode = ref(window.innerWidth < 768 ? 'grid' : 'list')
const selected = ref([])
const selectedIds = computed(() => selected.value.map((f) => f.id))
const multiSelect = ref(false)
const tableRef = ref()
// 当前文件夹的有效权限（view/edit/download/upload），由后端按文件夹规则实时计算
const currentPerms = ref([])
const currentFolder = ref({ granted: false, ownerName: '' })
const PERM_LABELS = { view: '查看', edit: '编辑', download: '下载', upload: '上传', share_internal: '内部分享', share_external: '外部分享' }
const PERM_DEFS = [
  { value: 'view', label: '文件查看' },
  { value: 'edit', label: '文件编辑' },
  { value: 'download', label: '文件下载' },
  { value: 'upload', label: '文件上传' },
  { value: 'share_internal', label: '内部分享' },
  { value: 'share_external', label: '外部分享' },
]
function labelOfPerm(p) { return PERM_LABELS[p] || p }
const canUpload = computed(() => currentPerms.value.includes('upload'))
function has(f, p) {
  if (!f) return false
  return Array.isArray(f.perms) ? f.perms.includes(p) : true
}
function canShare(f) { return has(f, 'share_internal') || has(f, 'share_external') }
function allHave(p) {
  return selected.value.length > 0 && selected.value.every((f) => has(f, p))
}
async function loadPerms() {
  try {
    const info = (await fileApi.folderInfo(currentId.value)).data || {}
    currentPerms.value = info.perms || []
    currentFolder.value = { granted: !!info.granted, ownerName: info.ownerName || '', name: info.name || '' }
  } catch (e) { currentPerms.value = []; currentFolder.value = { granted: false, ownerName: '' } }
}

let timer = null
function debouncedLoad() { clearTimeout(timer); timer = setTimeout(loadFiles, 300) }
async function loadFiles() {
  loading.value = true
  try {
    files.value = (await fileApi.list(currentId.value, keyword.value)).data
    selected.value = []
    await loadPerms()
  } catch (e) {} finally { loading.value = false }
}
async function loadTree() { try { treeData.value = (await fileApi.tree()).data } catch (e) {} }
async function loadCrumb() { try { breadcrumbs.value = (await fileApi.breadcrumb(currentId.value)).data } catch (e) {} }
function loadAll() { loadFiles(); loadTree(); loadCrumb() }
onMounted(() => {
  const deep = Number(route.query.folder || 0)
  if (deep > 0) currentId.value = deep
  loadAll(); loadUploadCfg(); checkSavedUploads()
})

function goFolder(id, idx) { currentId.value = id; breadcrumbs.value = breadcrumbs.value.slice(0, idx + 1); keyword.value = ''; loadFiles() }
function goUp() { if (breadcrumbs.value.length > 1) goFolder(breadcrumbs.value[breadcrumbs.value.length - 2].id, breadcrumbs.value.length - 2) }
function onTreeNode(n) { goFolder(n.id, 0); loadCrumb() }
// 双击：目录进入，文件统一先进预览；要编辑再点「编辑」按钮
function onDblClick(row) { row.isDir ? enter(row) : preview(row) }
function enter(row) { currentId.value = row.id; keyword.value = ''; loadFiles(); loadCrumb() }
function onRowClick(row, e) {
  if (e.ctrlKey || e.metaKey || e.shiftKey) return
  if (!multiSelect.value) return
  toggleSel(row)
}
function onGridClick(row) {
  if (row.isDir && !multiSelect.value) return enter(row)
  if (!multiSelect.value) return
  toggleSel(row)
}
function toggleSel(row) {
  const i = selectedIds.value.indexOf(row.id)
  if (i >= 0) selected.value = selected.value.filter((x) => x.id !== row.id)
  else selected.value = [...selected.value, row]
}
function onSelChange(v) { if (!multiSelect.value) selected.value = v }
function rowClass({ row }) { return selectedIds.value.includes(row.id) ? 'sel-row' : '' }
function exitMulti() { multiSelect.value = false; selected.value = []; tableRef.value?.clearSelection() }

function size(n) {
  if (!n) return '0 B'
  const u = ['B', 'KB', 'MB', 'GB', 'TB'], i = Math.min(Math.floor(Math.log(n) / Math.log(1024)), 4)
  return (n / Math.pow(1024, i)).toFixed(i ? 1 : 0) + ' ' + u[i]
}
const IMAGE_EXT = ['png', 'jpg', 'jpeg', 'gif', 'webp', 'bmp', 'svg']
const VIDEO_EXT = ['mp4', 'mov', 'webm', 'ogv', 'm4v']
const AUDIO_EXT = ['mp3', 'm4a', 'wav', 'flac', 'ogg', 'aac']
function isImage(f) { return !!f && !f.isDir && IMAGE_EXT.includes(f.ext) }
function isVideo(f) { return !!f && !f.isDir && VIDEO_EXT.includes(f.ext) }
function isAudio(f) { return !!f && !f.isDir && AUDIO_EXT.includes(f.ext) }
function canEdit(f) { return !f.isDir && ['txt', 'md', 'csv', 'json', 'html'].includes(f.ext) }
function iconOf(f) {
  if (f.isDir) return Folder
  if (isImage(f)) return Picture
  if (isVideo(f)) return VideoCamera
  if (isAudio(f)) return Headset
  return canEdit(f) ? Tickets : Document
}
function iconColor(f) { return f.isDir ? '#e0a030' : isImage(f) ? '#3a9bd8' : canEdit(f) ? '#4caf7d' : '#8492a6' }
function previewUrl(id) { return fileApi.previewUrl(id) }

// 文件夹 / 重命名
const createVisible = ref(false), newName = ref('')
function openCreateFolder() {
  if (!canUpload.value) { ElMessage.warning('当前文件夹没有上传权限'); return }
  newName.value = ''; createVisible.value = true; fabVisible.value = false
}
async function doCreate() {
  if (!newName.value.trim()) return
  try { await fileApi.createFolder({ parent_id: currentId.value, name: newName.value }); ElMessage.success('创建成功'); createVisible.value = false; loadAll() }
  catch (e) { ElMessage.error(e.message) }
}
const renameVisible = ref(false), renameName = ref(''), renameTarget = ref(null)
function openRename(row) { renameTarget.value = row; renameName.value = row.name; renameVisible.value = true }
async function doRename() {
  try { await fileApi.rename(renameTarget.value.id, renameName.value); ElMessage.success('重命名成功'); renameVisible.value = false; loadAll() }
  catch (e) { ElMessage.error(e.message) }
}

// 复制 / 剪切 / 粘贴
const clipboard = ref(null)
function setClipboard(mode) { clipboard.value = { ids: selectedIds.value, mode }; ElMessage.success(`已${mode === 'copy' ? '复制' : '剪切'}，请到目标目录粘贴`) }
async function pasteClipboard() {
  try {
    const r = await (clipboard.value.mode === 'copy' ? fileApi.copy : fileApi.move)(clipboard.value.ids, currentId.value)
    ElMessage.success(r.msg)
    if (clipboard.value.mode === 'move') clipboard.value = null
    loadAll()
  } catch (e) { ElMessage.error(e.message) }
}

// 删除
async function deleteSelected() {
  try {
    await ElMessageBox.confirm(`删除选中的 ${selected.value.length} 项？`, '提示', { type: 'warning' })
    await fileApi.remove(selectedIds.value); ElMessage.success('已移入回收站'); exitMulti(); loadAll()
  } catch (e) { if (e !== 'cancel') ElMessage.error(e.message) }
}

// 打包下载：后端 POST /download-zip
async function downloadZip(ids) {
  try {
    const res = await fetch(import.meta.env.BASE_URL.replace(/\/$/, '') + '/api/files/download-zip', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', Authorization: 'Bearer ' + localStorage.getItem('liteark_token') },
      body: JSON.stringify({ ids }),
    })
    if (!res.ok) throw new Error('请求失败（' + res.status + '）')
    const blob = await res.blob()
    const a = document.createElement('a')
    a.href = URL.createObjectURL(blob); a.download = 'liteark.zip'; a.click()
    URL.revokeObjectURL(a.href)
    ElMessage.success('正在下载')
  } catch (e) { ElMessage.error('打包失败: ' + e.message) }
}

// 下载：后端 POST /download-zip
async function downloadSelected() {
  const hasDir = selected.value.some((f) => f.isDir)
  if (hasDir) {
    await downloadZip(selectedIds.value)
  } else {
    if (isMobile.value) ElMessage.info('大批量文件建议在电脑端操作')
    selected.value.forEach((f) => window.open(fileApi.downloadUrl(f.id), '_blank'))
  }
}

// 单项下载（列表操作栏 / 移动面板）：文件直接下载，文件夹打包
function downloadItem(row) {
  if (!row) return
  if (row.isDir) downloadZip([row.id])
  else window.open(fileApi.downloadUrl(row.id), '_blank')
}

// 单项删除（列表操作栏 / 移动面板）
async function deleteItem(row) {
  if (!row) return
  try {
    await ElMessageBox.confirm(`删除「${row.name}」？`, '提示', { type: 'warning' })
    await fileApi.remove([row.id]); ElMessage.success('已移入回收站'); loadAll()
  } catch (e) { if (e !== 'cancel') ElMessage.error(e.message) }
}

// 分享
const shareVisible = ref(false), shareTarget = ref(null)
const shareUsers = ref([])
const shareForm = ref({ permission: 'view', password: '', expireDays: 7, maxDownloads: 0, shareType: 'external', targetIds: [] })
const shareUrl = ref('')
function canShareType(t) { return has(shareTarget.value, 'share_' + t) }
function openShare() { shareTarget.value = selected.value[0]; resetShare() }
function openShareOne(row) { shareTarget.value = row; resetShare() }
async function resetShare() {
  const type = canShareType('external') ? 'external' : 'internal'
  shareForm.value = { permission: 'view', password: '', expireDays: 7, maxDownloads: 0, shareType: type, targetIds: [] }
  shareUrl.value = ''
  shareVisible.value = true
  if (!shareUsers.value.length) { try { shareUsers.value = (await shareApi.users()).data || [] } catch (e) {} }
}
async function doShare() {
  const f = shareForm.value
  if (f.shareType === 'internal' && !f.targetIds.length) { ElMessage.warning('请选择要分享的内部用户'); return }
  try {
    const r = await shareApi.create({
      fileId: shareTarget.value.id, permission: f.permission, password: f.password,
      expireDays: f.expireDays, maxDownloads: f.maxDownloads,
      shareType: f.shareType, targetIds: f.targetIds,
    })
    // 后端返回 /s/{token}，子路径部署要补上 /liteark 前缀
    shareUrl.value = location.origin + import.meta.env.BASE_URL.replace(/\/$/, '') + r.data.url
    ElMessage.success(r.msg || '共享已创建')
  } catch (e) { ElMessage.error(e.message) }
}
function copyShare() { navigator.clipboard?.writeText(shareUrl.value).then(() => ElMessage.success('已复制')) }

// 文件夹授权：普通用户把自己的文件夹授权给同事（递归作用于全部下级）
const grantVisible = ref(false), grantTarget = ref(null)
const grantData = ref({ candidates: [], roles: [], rules: [], maxPerms: [] })
const grantForm = ref({ userId: null, mode: 'direct', roleId: null, perms: ['view', 'download'], inherit: true })
const selectedRole = computed(() => (grantData.value.roles || []).find((r) => r.id === grantForm.value.roleId) || null)
// 按角色授权时，“勾选”是上限，实际权限 = 角色默认权限 ∩ 上限
const grantEffectivePerms = computed(() => {
  if (grantForm.value.mode !== 'role') return grantForm.value.perms
  return (selectedRole.value?.perms || []).filter((p) => grantForm.value.perms.includes(p))
})
// 只能给自己拥有的文件夹授权；根目录不支持（权限必须挂在具体文件夹上）
const canGrantHere = computed(() => currentId.value > 0 && !currentFolder.value.granted && currentPerms.value.includes('edit'))
async function openGrant(folder) {
  grantTarget.value = folder || { id: currentId.value, name: currentFolder.value.name || '' }
  grantForm.value = { userId: null, mode: 'direct', roleId: null, perms: ['view', 'download'], inherit: true }
  grantData.value = { candidates: [], roles: [], rules: [], maxPerms: [] }
  grantVisible.value = true
  await loadGrant(grantTarget.value.id)
}
async function loadGrant(id) {
  try {
    const d = (await grantApi.detail(id)).data || {}
    grantData.value = {
      folderId: d.folderId, folderName: d.folderName, path: d.path,
      maxPerms: d.maxPerms || [], candidates: d.candidates || [],
      roles: d.roles || [], rules: d.rules || [],
    }
  } catch (e) {
    grantData.value = { candidates: [], roles: [], rules: [], maxPerms: [] }
    grantVisible.value = false
  }
}
// 选中同事时回填其已有授权（含是否按角色授权），避免误以为“重新授权”会追加
function onGrantUser(id) {
  const c = (grantData.value.candidates || []).find((x) => x.id === id)
  grantForm.value.perms = c && c.perms && c.perms.length ? [...c.perms] : ['view', 'download']
  grantForm.value.inherit = c ? c.inherit !== 0 : true
  grantForm.value.mode = c && c.sourceRoleId ? 'role' : 'direct'
  grantForm.value.roleId = c && c.sourceRoleId ? c.sourceRoleId : null
  if (grantForm.value.mode === 'role' && c.sourceRoleId) applyRolePerms()
}
function onGrantMode() {
  if (grantForm.value.mode === 'direct') {
    grantForm.value.roleId = null
  } else if (!grantForm.value.roleId && (grantData.value.roles || []).length) {
    // 默认选同事所属角色，减少一次点击
    const c = (grantData.value.candidates || []).find((x) => x.id === grantForm.value.userId)
    grantForm.value.roleId = c?.roleId || grantData.value.roles[0].id
    applyRolePerms()
  }
}
function onGrantRole() { applyRolePerms() }
// 按角色授权：勾选框用于设定上限，默认放开到“我能授出的全部范围”，实际权限由角色决定
function applyRolePerms() {
  const role = selectedRole.value
  if (!role) return
  grantForm.value.perms = [...(grantData.value.maxPerms || [])]
}
function grantPreset(kind) {
  const max = grantData.value.maxPerms || []
  const pick = kind === 'readonly' ? ['view', 'download']
    : kind === 'upload' ? ['view', 'download', 'upload']
    : ['view', 'edit', 'download', 'upload', 'share_internal', 'share_external']
  grantForm.value.perms = pick.filter((p) => max.includes(p))
}
async function saveGrant() {
  if (!grantForm.value.userId) { ElMessage.warning('请选择授权对象'); return }
  if (grantForm.value.mode === 'role' && !grantForm.value.roleId) { ElMessage.warning('请选择角色模板'); return }
  try {
    const r = await grantApi.save({
      folderId: grantData.value.folderId,
      userId: grantForm.value.userId,
      perms: grantForm.value.perms,
      inherit: grantForm.value.inherit ? 1 : 0,
      roleId: grantForm.value.mode === 'role' ? grantForm.value.roleId : null,
    })
    ElMessage.success(r.msg || '已保存')
    await loadGrant(grantData.value.folderId)
  } catch (e) { ElMessage.error(e.message) }
}
async function revokeGrant(row) {
  try {
    await ElMessageBox.confirm(`撤销「${row.subjectName}」在「${row.folderPath}」上的授权？撤销后该文件夹对其不可见。`, '提示', { type: 'warning' })
    await grantApi.revoke(row.id)
    ElMessage.success('已撤销')
    await loadGrant(grantData.value.folderId)
  } catch (e) { if (e !== 'cancel') ElMessage.error(e.message) }
}

// 编辑
const editorVisible = ref(false), editorFile = ref(null), editorContent = ref('')
/** initial 有值时直接用（从预览切过来），省一次重复下载 */
async function openEditor(row, initial) {
  editorFile.value = row
  editorContent.value = initial == null ? '加载中...' : initial
  editorVisible.value = true
  if (initial == null) {
    try {
      const r = await fetch(fileApi.previewUrl(row.id), { headers: { Authorization: 'Bearer ' + localStorage.getItem('liteark_token') } })
      editorContent.value = await r.text()
    } catch (e) { editorContent.value = '' }
  }
  lastSavedContent = editorContent.value
  if (autoSaveTimer) clearInterval(autoSaveTimer)
  autoSaveTimer = setInterval(() => { if (editorVisible.value) saveEditor(false) }, 30000)
}

/** 预览弹窗 → 编辑模式：带上已取到的原文（md/csv 也保留），不再重复请求 */
function switchToEdit() {
  const f = previewFile.value
  if (!f || !canEdit(f) || !has(f, 'edit')) return
  const cached = previewError.value ? null : (previewRaw.value || previewText.value || null)
  previewVisible.value = false
  openEditor(f, cached)
}
async function saveEditor(notify = true) {
  if (!editorFile.value || editorContent.value === lastSavedContent || editorContent.value === '加载中...') return
  try {
    const r = await fileApi.saveText(editorFile.value.id, editorContent.value)
    editorFile.value.version = r.data.version
    lastSavedContent = editorContent.value
    if (notify) ElMessage.success('已保存')
    loadFiles()
  } catch (e) { if (notify) ElMessage.error(e.message) }
}
async function onEditorClose() {
  if (autoSaveTimer) { clearInterval(autoSaveTimer); autoSaveTimer = null }
  if (editorFile.value && editorContent.value !== '加载中...') await saveEditor(false)
  editorFile.value = null
}

// 在线编辑：30s 自动保存（移动端同样适用，仅在内容变化时生成新版本）
let autoSaveTimer = null
let lastSavedContent = ''

// 预览与历史版本
const previewVisible = ref(false), previewFile = ref(null), previewLoading = ref(false)
const previewText = ref(''), previewRaw = ref(''), previewError = ref(''), mdHtml = ref(''), officeSrc = ref('')
const csvHeader = ref([]), csvRows = ref([]), csvTruncated = ref(false)
const versionsVisible = ref(false), versions = ref([]), versionFile = ref(null)
function isOffice(f) { return !!f && ['docx', 'doc', 'xlsx', 'xls', 'pptx', 'ppt'].includes(f.ext) }
function parseCsv(text) {
  const lines = text.split(/\r?\n/).filter((l) => l.length)
  if (!lines.length) return { header: [], rows: [], truncated: false }
  const split = (l) => l.split(/,(?=(?:[^"]*"[^"]*")*[^"]*$)/).map((s) => s.replace(/^"|"$/g, '').trim())
  const header = split(lines[0])
  const rows = lines.slice(1, 201).map((line) => {
    const cells = split(line), o = {}
    header.forEach((h, j) => { o['c' + j] = cells[j] || '' })
    return o
  })
  return { header, rows, truncated: lines.length > 201 }
}
async function preview(f) {
  previewFile.value = f; previewVisible.value = true
  previewText.value = ''; previewRaw.value = ''; mdHtml.value = ''; previewError.value = ''; officeSrc.value = ''
  csvHeader.value = []; csvRows.value = []; csvTruncated.value = false
  if (isImage(f) || isVideo(f) || isAudio(f) || f.ext === 'pdf') return
  if (isOffice(f)) {
    previewLoading.value = true
    try {
      const info = (await fileApi.officePreviewInfo(f.id)).data || {}
      if (info.available) officeSrc.value = fileApi.officePreviewUrl(f.id)
      else previewError.value = info.msg || 'Office 预览不可用，请下载后查看'
    } catch (e) { previewError.value = 'Office 预览失败: ' + (e.message || '未知错误') }
    finally { previewLoading.value = false }
    return
  }
  previewLoading.value = true
  try {
    const res = await fetch(fileApi.previewUrl(f.id), { headers: { Authorization: 'Bearer ' + localStorage.getItem('liteark_token') } })
    const text = await res.text()
    previewRaw.value = text
    if (f.ext === 'md') mdHtml.value = DOMPurify.sanitize(marked.parse(text))
    else if (f.ext === 'csv') {
      const p = parseCsv(text); csvHeader.value = p.header; csvRows.value = p.rows; csvTruncated.value = p.truncated
    } else previewText.value = text
  } catch (e) { previewError.value = '加载失败: ' + e.message }
  finally { previewLoading.value = false }
}
function downloadOne(f) { if (f) window.open(fileApi.downloadUrl(f.id), '_blank') }
function openPreviewExternal() {
  const f = previewFile.value
  if (!f) return
  window.open(isOffice(f) ? fileApi.officePreviewUrl(f.id) : previewUrl(f.id), '_blank')
}
async function openVersions(row) {
  if (!row || row.isDir) return
  versionFile.value = row; versionsVisible.value = true
  try { versions.value = (await fileApi.versions(row.id)).data } catch (e) { versions.value = [] }
}
async function doRollback(v) {
  try {
    const r = await fileApi.rollback(versionFile.value.id, v.id)
    ElMessage.success(r.msg); versionsVisible.value = false; editorVisible.value = false; loadFiles()
  } catch (e) { ElMessage.error(e.message) }
}

// 上传（分片 + 断点续传）
const queue = ref([]), uploadDrawer = ref(false), fabVisible = ref(false)
const uploadCfg = ref({ chunkSize: 2 * 1024 * 1024, concurrency: 2 })
async function loadUploadCfg() { try { uploadCfg.value = (await uploadApi.config()).data } catch (e) {} }
function openFab() { fabVisible.value = true }
function pickFiles() { fabVisible.value = false; const input = document.getElementById(isMobile.value ? 'native-file-mobile' : 'native-file'); if (input) input.click() }
function pickFolder() { const input = document.getElementById('native-folder'); if (input) input.click() }

// 拖拽上传：支持文件与文件夹（文件夹递归展开后平铺到当前目录）
const dropActive = ref(false)
let dropTimer = null
function onDragOver() {
  if (isMobile.value) return
  dropActive.value = true
  clearTimeout(dropTimer)
  dropTimer = setTimeout(() => { dropActive.value = false }, 200)
}
function onDragLeave() { dropActive.value = false }
async function onDrop(e) {
  dropActive.value = false
  if (isMobile.value) return
  if (!canUpload.value) { ElMessage.warning('当前文件夹没有上传权限'); return }
  const dt = e.dataTransfer
  if (!dt) return
  const files = []
  const items = dt.items
  if (items && items.length && items[0].webkitGetAsEntry) {
    const entries = []
    for (let i = 0; i < items.length; i++) {
      const en = items[i].webkitGetAsEntry()
      if (en) entries.push(en)
    }
    for (const en of entries) await walkEntry(en, files)
  } else {
    files.push(...Array.from(dt.files || []))
  }
  if (files.length) startUpload(files)
  else ElMessage.warning('没有识别到可上传的文件')
}
function walkEntry(entry, out) {
  return new Promise((resolve) => {
    if (entry.isFile) {
      entry.file((f) => { out.push(f); resolve() }, () => resolve())
    } else if (entry.isDirectory) {
      const reader = entry.createReader()
      const readNext = () => reader.readEntries(async (batch) => {
        if (!batch.length) return resolve()
        for (const en of batch) await walkEntry(en, out)
        readNext()
      }, () => resolve())
      readNext()
    } else {
      resolve()
    }
  })
}
function clickCamera() { fabVisible.value = false; document.getElementById('native-camera')?.click() }
function onFiles(e) {
  const list = Array.from(e.target.files || [])
  e.target.value = ''
  if (list.length) startUpload(list)
}
// 单次批量最多同时上传几个文件（每个文件内部还有分片并发，两者相乘才是总并发连接数）
const MAX_PARALLEL_UPLOADS = 3
let reloadTimer = null
/** 列表刷新合并：并行上传时多文件几乎同时完成，逐次 loadFiles 会引发无谓的重复请求 */
function scheduleReload() {
  clearTimeout(reloadTimer)
  reloadTimer = setTimeout(() => { loadFiles(); loadTree(); loadCrumb() }, 300)
}
/** 文件级并发池：同时最多 limit 个文件在传，其余排队；某个文件失败不影响后续文件 */
function runPool(items, limit) {
  let cursor = 0
  const n = Math.max(1, Math.min(limit, items.length))
  const worker = async () => {
    while (cursor < items.length) {
      const item = items[cursor++]
      try { await item.start() } catch (e) { /* start() 内部已把异常转成失败状态 */ }
    }
  }
  return Promise.all(Array.from({ length: n }, worker))
}
async function startUpload(list) {
  if (!canUpload.value) { ElMessage.warning('当前文件夹没有上传权限'); loadPerms(); return }
  uploadDrawer.value = true
  const clientType = isMobile.value ? 'Mobile' : 'PC'
  const runners = []
  const results = []
  // 浏览器对同一域名（HTTP/1.1）最多 6 个并发连接，超出的请求会在浏览器里排队，
  // 表现就是最后那个文件长时间停在 0%。按同时在传的文件数把分片并发均分到这 6 个连接上。
  const slots = Math.max(1, Math.min(MAX_PARALLEL_UPLOADS, list.length))
  const perFileConcurrency = Math.max(1, Math.min(uploadCfg.value.concurrency || 2, Math.floor(6 / slots)))
  for (const f of list) {
    const saved = queue.value.find((q) => q.needsFile && q.name === f.name && q.size === f.size)
    // 必须包成响应式：push 进队列的是原始对象，直接改它的属性不会触发重渲染，
    // 表现就是进度一直停在 0%（文件其实传完了，界面不动）。
    const task = reactive(saved || { id: uid(), name: f.name, size: f.size, progress: 0, status: 'waiting', statusText: '排队中', clientType })
    if (saved) { task.needsFile = false; task.status = 'waiting'; task.statusText = '继续上传' }
    else queue.value.push(task)
    const runner = new UploadRunner(f, {
      id: task.id, parentId: currentId.value, clientType,
      chunkSize: saved?.chunkSize || uploadCfg.value.chunkSize,
      concurrency: perFileConcurrency,
      md5: saved?.md5 || '', uploadId: saved?.uploadId || '', uploaded: saved?.uploaded || [],
      onChange: (r) => {
        task.progress = r.progress; task.status = r.status; task.statusText = r.statusText
        task.md5 = r.md5; task.uploadId = r.uploadId; task.uploaded = r.uploaded; task.chunkTotal = r.chunkTotal
      },
      onDone: (r, failed, data) => {
        results.push({ name: r.name, failed: !!failed, cancelled: !!r.cancelled, skipped: !!(data && data.skipped) })
        if (!failed) scheduleReload()
      },
    })
    // markRaw：runner 是普通类实例，交给 Vue 代理只会白白增加开销
    task.runner = markRaw(runner)
    runners.push(runner)
  }
  // 先把所有文件都塞进队列再并发开跑：队列能立刻看到全部待传文件，而不是一个个往外冒
  await runPool(runners, MAX_PARALLEL_UPLOADS)
  const ok = results.filter((r) => !r.failed && !r.cancelled)
  const cancelled = results.filter((r) => r.cancelled).length
  const failed = results.length - ok.length - cancelled
  if (!results.length) return
  if (results.length === 1) {
    const r0 = results[0]
    if (r0.cancelled) return
    if (r0.failed) ElMessage.error(`${r0.name} 上传失败，可在上传队列查看`)
    else if (r0.skipped) ElMessage.warning(`${r0.name} 已存在，已跳过`)
    else ElMessage.success(`${r0.name} 上传完成`)
    return
  }
  // 批量上传只汇总一条提示，避免十几个文件轮番弹窗
  const rest = Math.max(0, list.length - results.length)  // 被暂停或中途取消、未走完流程的
  const suffix = rest ? `，另有 ${rest} 个未完成（可在队列继续）` : ''
  if (!failed && !cancelled) ElMessage.success(`${ok.length} 个文件上传完成${suffix}`)
  else if (!ok.length) ElMessage.error(`上传失败 ${failed} 个${cancelled ? `，取消 ${cancelled} 个` : ''}${suffix}，可在上传队列查看`)
  else ElMessage.warning(`上传结束：成功 ${ok.length} 个，失败 ${failed} 个${cancelled ? `，取消 ${cancelled} 个` : ''}${suffix}`)
}
function pauseTask(t) { t.runner?.pause() }
function resumeTask(t) { t.runner?.resume() }
function cancelTask(t) { t.runner?.cancel() }
async function checkSavedUploads() {
  const saved = readSaved().filter((t) => t.status !== 'done' && t.status !== 'cancelled')
  if (!saved.length) return
  try {
    await ElMessageBox.confirm(`发现 ${saved.length} 个未完成上传。浏览器无法直接恢复本地文件，请重新选择相同文件继续，服务端已完成的分片不会重传。`, '断点续传', { confirmButtonText: '重新选择文件', cancelButtonText: '稍后处理' })
    uploadDrawer.value = true
    saved.forEach((t) => {
      if (queue.value.some((q) => q.id === t.id)) return
      queue.value.push({ id: t.id, name: t.name, size: t.size, progress: 0, status: 'paused',
        statusText: '待重新选择文件', clientType: t.clientType, needsFile: true,
        md5: t.md5, uploadId: t.uploadId, uploaded: t.uploaded, chunkSize: t.chunkSize })
    })
  } catch (e) { /* 用户暂不处理 */ }
}

// 移动单项操作面板
const actionsVisible = ref(false), actionTarget = ref(null)
function openActions(row) { actionTarget.value = row; actionsVisible.value = true }
async function doAction(a) {
  const row = actionTarget.value; actionsVisible.value = false
  if (a === 'rename') openRename(row)
  else if (a === 'preview') preview(row)
  else if (a === 'share') openShareOne(row)
  else if (a === 'grant') openGrant(row)
  else if (a === 'download') downloadItem(row)
  else if (a === 'delete') deleteItem(row)
}
</script>

<style scoped>
.disk-page { height: 100%; position: relative; }
.disk-main { display: flex; height: 100%; }
.tree-panel { width: 220px; background: #fff; border-right: 1px solid var(--la-border); flex-shrink: 0; overflow: auto; }
.tree-title { padding: 16px 20px 8px; font-weight: 600; color: #5c6b7a; font-size: 13px; }
.tree-node { display: flex; align-items: center; gap: 6px; }
.file-panel { flex: 1; display: flex; flex-direction: column; min-width: 0; }
.toolbar { background: #fff; padding: 10px 18px; display: flex; align-items: center; justify-content: space-between; gap: 12px; flex-wrap: wrap; }
.toolbar-right { display: flex; align-items: center; gap: 8px; }
.search-input { width: 180px; }
.crumb { font-size: 13px; }
.action-bar { background: #fff; padding: 8px 18px; display: flex; align-items: center; gap: 6px; border-top: 1px solid var(--la-border); flex-wrap: wrap; }
.action-bar.multi-mode { background: #eef6f4; border-bottom: 2px solid var(--la-primary); }
.spacer { flex: 1; }
.selected-tip { color: var(--la-primary); font-size: 12px; }
.file-area { flex: 1; overflow: hidden; background: #fff; margin: 12px 16px 16px; border: 1px solid var(--la-border); border-radius: 8px; }
.file-area { position: relative; }
.file-area.drop-active::after {
  content: '松开鼠标即可上传'; position: absolute; inset: 6px; z-index: 5;
  display: flex; align-items: center; justify-content: center;
  border: 2px dashed #409eff; border-radius: 8px; background: rgba(64, 158, 255, 0.08);
  color: #409eff; font-size: 14px; pointer-events: none;
}
.name-cell { display: flex; align-items: center; gap: 8px; min-width: 0; }
.name-cell span { white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.shared-tag { flex-shrink: 0; transform: scale(.9); transform-origin: left center; }
.grant-banner { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; margin: 10px 16px 0;
  padding: 8px 12px; background: #f2f8f6; border: 1px solid #d6e9e2; border-radius: 6px; font-size: 13px; color: #445; }
.grant-banner .muted-tip { color: var(--la-muted); }
:deep(.sel-row) { --el-table-tr-bg-color: #e7f3f0; }
.grid-view { display: flex; flex-wrap: wrap; gap: 12px; padding: 14px; overflow: auto; height: 100%; }
.grid-item { width: 132px; padding: 10px 6px; border: 2px solid transparent; border-radius: 8px; text-align: center; position: relative; }
.grid-item.selected { border-color: var(--la-primary); background: #e7f3f0; }
.grid-thumb { height: 84px; display: flex; align-items: center; justify-content: center; position: relative; }
.thumb { width: 88px; height: 76px; border-radius: 4px; }
.check-mark { position: absolute; top: 2px; left: 2px; width: 22px; height: 22px; border: 2px solid #bbb; border-radius: 50%; display: flex; align-items: center; justify-content: center; background: #fff; }
.check-mark.on { background: var(--la-primary); color: #fff; border-color: var(--la-primary); }
.grid-name { font-size: 13px; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.grid-meta { font-size: 11px; color: #9aa7b4; }
.grid-more { position: absolute; top: 4px; right: 2px; }
.fab { position: fixed; right: 18px; bottom: calc(var(--la-tabbar) + 18px); width: 56px !important; height: 56px !important; z-index: 90; box-shadow: 0 4px 16px rgba(31,107,96,.4); }
.fab-actions { display: flex; gap: 12px; justify-content: center; flex-wrap: wrap; }
.fab-actions.vertical { flex-direction: column; }
.fab-actions .el-button { min-height: 46px; }
.fab-tip { text-align: center; color: var(--la-muted); font-size: 12px; margin-top: 14px; }
.upload-item { padding: 12px 0; border-bottom: 1px solid #eee; }
.upload-head { display: flex; justify-content: space-between; font-size: 13px; margin-bottom: 8px; }
.upload-foot { display: flex; justify-content: space-between; font-size: 12px; color: var(--la-muted); margin-top: 6px; }
.empty-tip { text-align: center; color: var(--la-muted); padding: 40px 0; }
.share-file { font-size: 16px; font-weight: 600; margin-bottom: 16px; padding: 10px; background: #f2f7f6; border-radius: 6px; }
.grant-note { display: flex; gap: 8px; align-items: flex-start; padding: 9px 12px; background: #f2f8f6;
  border: 1px solid #d6e9e2; border-radius: 6px; font-size: 12.5px; color: #46586b; line-height: 1.7; }
.grant-note .el-icon { margin-top: 2px; color: var(--la-primary); flex-shrink: 0; }
.grant-path { font-size: 12px; color: var(--la-muted); margin-top: 8px; word-break: break-all; }
.grant-form { margin-top: 12px; }
.grant-perms { display: flex; align-items: center; gap: 14px; flex-wrap: wrap; }
.grant-role-note { margin-top: 6px; font-size: 12px; color: #5c6b7a; line-height: 1.7; background: #f6f9fb; border-radius: 6px; padding: 7px 10px; }
.grant-perms .el-button-group { margin-left: auto; }
.grant-hint { font-size: 12px; color: var(--la-muted); line-height: 1.6; margin-top: 2px; }
.grant-rules-title { font-size: 13px; font-weight: 600; color: #5c6b7a; margin: 18px 0 8px; }
.perm-tag { margin-right: 6px; }
.share-result { display: flex; gap: 8px; margin-top: 10px; }
.editor-area :deep(textarea) { font-family: Menlo, Consolas, monospace; font-size: 13px; line-height: 1.6; }
.editor-tip { font-size: 12px; color: var(--la-muted); margin-top: 8px; }
.preview-dialog { min-height: 300px; }
.preview-image { width: 100%; max-height: 60vh; }
.preview-video { width: 100%; max-height: 65vh; background: #000; border-radius: 6px; }
.preview-audio { width: 100%; margin: 24px 0; }
.preview-frame { width: 100%; height: 60vh; border: 1px solid var(--la-border); }
.md-body { max-height: 60vh; overflow: auto; line-height: 1.8; padding: 0 8px; }
.md-body :deep(img) { max-width: 100%; }
.md-body :deep(table) { border-collapse: collapse; width: 100%; margin: 12px 0; font-size: 13px; }
.md-body :deep(th), .md-body :deep(td) { border: 1px solid var(--la-border); padding: 7px 10px; text-align: left; }
.md-body :deep(th) { background: #f2f6f5; font-weight: 600; }
.md-body :deep(pre) { background: #f6f8f8; border: 1px solid var(--la-border); border-radius: 6px; padding: 12px; overflow: auto; }
.md-body :deep(code) { font-family: Menlo, Consolas, monospace; font-size: 12.5px; }
.md-body :deep(blockquote) { margin: 10px 0; padding: 6px 12px; border-left: 3px solid var(--la-primary); background: #f6fafa; color: #55636f; }
.preview-text { white-space: pre-wrap; word-break: break-all; max-height: 60vh; overflow: auto; font-size: 13px; line-height: 1.7; }
.preview-error { color: #d9534f; margin-top: 12px; }
.preview-actions { margin-top: 8px; text-align: right; }
@media (max-width: 767px) {
  .file-area { margin: 8px; }
  .tree-panel { display: none; }
  .search-input { width: 130px; }
  .bottom-sheet :deep(.el-dialog__body) { padding-top: 8px; }
}
</style>
