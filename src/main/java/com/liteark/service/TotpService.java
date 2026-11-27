package com.liteark.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.liteark.common.BizException;
import com.liteark.config.LiteArkProperties;
import com.liteark.entity.SystemConfig;
import com.liteark.entity.TotpRecoveryCode;
import com.liteark.entity.TrustedDevice;
import com.liteark.entity.User;
import com.liteark.entity.UserTotp;
import com.liteark.mapper.SystemConfigMapper;
import com.liteark.mapper.TotpRecoveryCodeMapper;
import com.liteark.mapper.TrustedDeviceMapper;
import com.liteark.mapper.UserMapper;
import com.liteark.mapper.UserTotpMapper;
import com.liteark.util.ClientUtil;
import lombok.RequiredArgsConstructor;
import org.apache.commons.codec.binary.Base32;
import org.apache.commons.codec.binary.Hex;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import javax.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** TOTP 二次验证核心：密钥/恢复码加密存储、验证码校验、防重放、信任设备与锁定。 */
@Service
@RequiredArgsConstructor
public class TotpService {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int STEP_SECONDS = 30;

    private final UserMapper userMapper;
    private final UserTotpMapper totpMapper;
    private final TotpRecoveryCodeMapper recoveryMapper;
    private final TrustedDeviceMapper deviceMapper;
    private final SystemConfigMapper configMapper;
    private final LiteArkProperties props;

    // ===== 配置 =====

    public String config(String key, String def) {
        SystemConfig c = configMapper.selectById(key);
        return c == null || c.getConfigValue() == null || c.getConfigValue().trim().isEmpty()
                ? def : c.getConfigValue().trim();
    }

    public int configInt(String key, int def) {
        try { return Integer.parseInt(config(key, String.valueOf(def))); } catch (Exception e) { return def; }
    }

    public boolean globalEnabled() {
        return Boolean.parseBoolean(config("totp_global_enabled", "false"));
    }

    public boolean allowed(User u) {
        return u != null && (u.getTotpAllowed() == null || u.getTotpAllowed() == 1);
    }

    public boolean isEnabled(Long userId) {
        UserTotp t = userId == null ? null : totpMapper.selectById(userId);
        return t != null && t.getEnabled() != null && t.getEnabled() == 1;
    }

    public String disablePolicy() {
        String p = config("totp_disable_policy", "A").toUpperCase();
        return "B".equals(p) ? "B" : "A";
    }

    // ===== 用户自助状态 =====

    public Map<String, Object> status(User u) {
        boolean enabled = isEnabled(u.getId());
        boolean allowed = allowed(u);
        boolean global = globalEnabled();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("globalEnabled", global);
        m.put("allowed", allowed);
        m.put("enabled", enabled);
        // 平台总开关关闭或管理员禁止时，账号安全页隐藏二次验证模块；登录校验不受影响
        m.put("visible", global && allowed);
        m.put("canBind", global && allowed && !enabled);
        m.put("recoveryCount", enabled ? recoveryMapper.selectCount(new LambdaQueryWrapper<TotpRecoveryCode>()
                .eq(TotpRecoveryCode::getUserId, u.getId()).eq(TotpRecoveryCode::getUsed, 0)) : 0);
        m.put("deviceCount", enabled ? deviceMapper.selectCount(new LambdaQueryWrapper<TrustedDevice>()
                .eq(TrustedDevice::getUserId, u.getId())) : 0);
        return m;
    }

    /** 发起绑定：生成密钥并暂存（未启用），返回给前端本地生成二维码。 */
    public Map<String, Object> beginBind(User u) {
        if (!globalEnabled()) throw new BizException("二次验证总开关未开启");
        if (!allowed(u)) throw new BizException("管理员未允许该账号开启二次验证");
        UserTotp old = totpMapper.selectById(u.getId());
        if (old != null && old.getEnabled() != null && old.getEnabled() == 1) {
            throw new BizException("该账号已开启二次验证");
        }
        byte[] raw = new byte[20];
        RANDOM.nextBytes(raw);
        String secret = new Base32().encodeToString(raw).replace("=", "");
        UserTotp t = old == null ? new UserTotp() : old;
        t.setUserId(u.getId());
        t.setSecretCipher(encrypt(secret));
        t.setEnabled(0);
        t.setFailedCount(0);
        t.setLockedUntil(null);
        t.setLastUsedStep(0L);
        t.setCreatedAt(t.getCreatedAt() == null ? now() : t.getCreatedAt());
        t.setUpdatedAt(now());
        if (old == null) totpMapper.insert(t); else totpMapper.updateById(t);
        // 重新绑定时旧恢复码立即作废
        recoveryMapper.delete(new LambdaQueryWrapper<TotpRecoveryCode>().eq(TotpRecoveryCode::getUserId, u.getId()));

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("secret", secret);
        m.put("otpauthUri", otpauthUri(u, secret));
        return m;
    }

    /** 校验绑定验证码；失败时删除暂存密钥（校验失败不保存密钥）。 */
    public List<String> confirmBind(User u, String code) {
        UserTotp t = totpMapper.selectById(u.getId());
        if (t == null || t.getEnabled() != null && t.getEnabled() == 1) {
            throw new BizException("请先发起绑定");
        }
        boolean ok;
        try {
            ok = checkCode(t, code, true);
        } catch (RuntimeException e) {
            totpMapper.deleteById(u.getId());
            throw e;
        }
        if (!ok) {
            totpMapper.deleteById(u.getId());
            throw new BizException(lastCodeMessage(t));
        }
        t.setEnabled(1);
        t.setFailedCount(0);
        t.setLockedUntil(null);
        t.setUpdatedAt(now());
        totpMapper.updateById(t);
        return generateRecoveryCodes(u.getId());
    }

    public void unbind(User u, String code, String recoveryCode) {
        requireEnabled(u.getId());
        boolean ok = false;
        if (code != null && !code.trim().isEmpty()) ok = verifyCode(u, code);
        else if (recoveryCode != null && !recoveryCode.trim().isEmpty()) ok = consumeRecoveryCode(u, recoveryCode);
        else throw new BizException("请输入动态验证码或备用恢复码");
        if (!ok) throw new BizException("核验未通过");
        reset(u.getId());
    }

    public List<String> regenerateRecovery(User u, String code, String recoveryCode) {
        requireEnabled(u.getId());
        boolean ok = false;
        if (code != null && !code.trim().isEmpty()) ok = verifyCode(u, code);
        else if (recoveryCode != null && !recoveryCode.trim().isEmpty()) ok = consumeRecoveryCode(u, recoveryCode);
        else throw new BizException("请输入动态验证码或备用恢复码");
        if (!ok) throw new BizException("核验未通过");
        return generateRecoveryCodes(u.getId());
    }

    /** 管理员或用户重置：清空密钥、恢复码与全部信任设备。 */
    public boolean reset(Long userId) {
        UserTotp t = totpMapper.selectById(userId);
        totpMapper.deleteById(userId);
        recoveryMapper.delete(new LambdaQueryWrapper<TotpRecoveryCode>().eq(TotpRecoveryCode::getUserId, userId));
        deviceMapper.delete(new LambdaQueryWrapper<TrustedDevice>().eq(TrustedDevice::getUserId, userId));
        return t != null && t.getEnabled() != null && t.getEnabled() == 1;
    }

    // ===== 登录校验 =====

    public boolean verifyCode(User u, String code) {
        requireEnabled(u.getId());
        UserTotp t = totpMapper.selectById(u.getId());
        boolean ok = checkCode(t, code, true);
        if (!ok) throw new BizException(lastCodeMessage(t));
        return true;
    }

    public boolean consumeRecoveryCode(User u, String code) {
        requireEnabled(u.getId());
        if (code == null || code.trim().isEmpty()) throw new BizException("请输入备用恢复码");
        String hash = hash("recovery", u.getId() + ":" + normalizeCode(code));
        TotpRecoveryCode row = recoveryMapper.selectOne(new LambdaQueryWrapper<TotpRecoveryCode>()
                .eq(TotpRecoveryCode::getUserId, u.getId())
                .eq(TotpRecoveryCode::getCodeHash, hash)
                .eq(TotpRecoveryCode::getUsed, 0)
                .last("limit 1"));
        if (row == null) throw new BizException("恢复码无效或已使用");
        row.setUsed(1);
        row.setUsedAt(now());
        recoveryMapper.updateById(row);
        // 恢复码是应急通道，成功后解除验证码锁定但不新增信任设备
        UserTotp t = totpMapper.selectById(u.getId());
        if (t != null) {
            t.setFailedCount(0);
            t.setLockedUntil(null);
            t.setUpdatedAt(now());
            totpMapper.updateById(t);
        }
        return true;
    }

    public boolean isLocked(Long userId) {
        UserTotp t = totpMapper.selectById(userId);
        return t != null && locked(t);
    }

    public boolean isTrusted(Long userId, HttpServletRequest request) {
        if (userId == null) return false;
        purgeExpiredDevices(userId);
        String hash = deviceHash(userId, request);
        return deviceMapper.selectCount(new LambdaQueryWrapper<TrustedDevice>()
                .eq(TrustedDevice::getUserId, userId)
                .eq(TrustedDevice::getDeviceHash, hash)
                .gt(TrustedDevice::getExpireAt, now())) > 0;
    }

    public void trustDevice(Long userId, HttpServletRequest request) {
        String hash = deviceHash(userId, request);
        int days = Math.max(1, configInt("trusted_device_ttl_days", 30));
        TrustedDevice old = deviceMapper.selectOne(new LambdaQueryWrapper<TrustedDevice>()
                .eq(TrustedDevice::getUserId, userId).eq(TrustedDevice::getDeviceHash, hash).last("limit 1"));
        TrustedDevice d = old == null ? new TrustedDevice() : old;
        d.setUserId(userId);
        d.setDeviceHash(hash);
        d.setDeviceName(deviceName(request));
        d.setIp(ClientUtil.normalizeIp(ClientUtil.ip(request)));
        d.setClientType(ClientUtil.clientType(request));
        d.setCreatedAt(now());
        d.setExpireAt(fmt(System.currentTimeMillis() + days * 86400000L));
        if (old == null) deviceMapper.insert(d); else deviceMapper.updateById(d);
    }

    public List<TrustedDevice> devices(Long userId) {
        purgeExpiredDevices(userId);
        return deviceMapper.selectList(new LambdaQueryWrapper<TrustedDevice>()
                .eq(TrustedDevice::getUserId, userId).orderByDesc(TrustedDevice::getCreatedAt));
    }

    public TrustedDevice device(Long userId, Long deviceId) {
        return deviceMapper.selectOne(new LambdaQueryWrapper<TrustedDevice>()
                .eq(TrustedDevice::getId, deviceId).eq(TrustedDevice::getUserId, userId).last("limit 1"));
    }

    public void deleteDevice(Long userId, Long deviceId) {
        deviceMapper.delete(new LambdaQueryWrapper<TrustedDevice>()
                .eq(TrustedDevice::getId, deviceId).eq(TrustedDevice::getUserId, userId));
    }

    /** 清理过期信任设备，返回清理条数，由调用方写审计。 */
    public int purgeExpiredDevices(Long userId) {
        return deviceMapper.delete(new LambdaQueryWrapper<TrustedDevice>()
                .eq(TrustedDevice::getUserId, userId).lt(TrustedDevice::getExpireAt, now()));
    }

    public String deviceName(HttpServletRequest request) {
        String n = request.getHeader("X-Device-Name");
        if (n != null && !n.trim().isEmpty()) return n.trim().substring(0, Math.min(60, n.trim().length()));
        return ClientUtil.clientType(request) + " 浏览器";
    }

    // ===== 内部实现 =====

    private void requireEnabled(Long userId) {
        if (!isEnabled(userId)) throw new BizException("该账号未开启二次验证");
    }

    private List<String> generateRecoveryCodes(Long userId) {
        recoveryMapper.delete(new LambdaQueryWrapper<TotpRecoveryCode>().eq(TotpRecoveryCode::getUserId, userId));
        int count = Math.max(1, Math.min(50, configInt("totp_recovery_count", 10)));
        String batch = Long.toHexString(System.currentTimeMillis()) + "-" + Integer.toHexString(RANDOM.nextInt());
        List<String> plain = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            byte[] raw = new byte[10];
            RANDOM.nextBytes(raw);
            String code = new Base32().encodeToString(raw).replace("=", "");
            code = code.replaceAll("(.{4})(?=.)", "$1-");
            TotpRecoveryCode r = new TotpRecoveryCode();
            r.setUserId(userId);
            r.setCodeHash(hash("recovery", userId + ":" + normalizeCode(code)));
            r.setUsed(0);
            r.setBatch(batch);
            r.setCreatedAt(now());
            recoveryMapper.insert(r);
            plain.add(code);
        }
        return plain;
    }

    /** 校验动态验证码；返回 false 表示错误或重放，失败计数在内部落库。 */
    private boolean checkCode(UserTotp t, String code, boolean countFailure) {
        if (t == null || code == null || !code.trim().matches("\\d{6}")) {
            if (countFailure && t != null) fail(t);
            return false;
        }
        if (locked(t)) throw new BizException("验证码错误次数过多，账号已临时锁定，请使用恢复码登录或联系管理员重置");
        String secret;
        try { secret = decrypt(t.getSecretCipher()); } catch (Exception e) { return false; }
        long nowStep = System.currentTimeMillis() / 1000 / STEP_SECONDS;
        int window = Math.max(0, Math.min(5, configInt("totp_window", 1)));
        Long matched = null;
        for (int i = -window; i <= window; i++) {
            long step = nowStep + i;
            if (step <= 0) continue;
            if (totp(secret, step) == Integer.parseInt(code)) { matched = step; break; }
        }
        long used = t.getLastUsedStep() == null ? 0 : t.getLastUsedStep();
        if (matched != null && matched > used) {
            t.setLastUsedStep(matched);
            t.setFailedCount(0);
            t.setLockedUntil(null);
            t.setUpdatedAt(now());
            totpMapper.updateById(t);
            return true;
        }
        if (countFailure) fail(t);
        return false;
    }

    private String lastCodeMessage(UserTotp t) {
        if (t != null && locked(t)) return "验证码错误次数过多，账号已临时锁定，请使用恢复码登录或联系管理员重置";
        int max = Math.max(1, configInt("totp_max_attempts", 5));
        int used = t == null || t.getFailedCount() == null ? max : t.getFailedCount();
        return "验证码错误或已使用，还可尝试 " + Math.max(0, max - used) + " 次";
    }

    private void fail(UserTotp t) {
        int max = Math.max(1, configInt("totp_max_attempts", 5));
        int failed = (t.getFailedCount() == null ? 0 : t.getFailedCount()) + 1;
        t.setFailedCount(failed);
        if (failed >= max) {
            int minutes = Math.max(1, configInt("totp_lock_minutes", 30));
            t.setLockedUntil(fmt(System.currentTimeMillis() + minutes * 60000L));
        }
        t.setUpdatedAt(now());
        totpMapper.updateById(t);
    }

    private boolean locked(UserTotp t) {
        String until = t.getLockedUntil();
        return until != null && !until.trim().isEmpty() && until.compareTo(now()) > 0;
    }

    private int totp(String secret, long step) {
        try {
            byte[] key = new Base32().decode(secret);
            byte[] msg = new byte[8];
            for (int i = 7; i >= 0; i--) { msg[i] = (byte) (step & 0xff); step >>= 8; }
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(key, "HmacSHA1"));
            byte[] h = mac.doFinal(msg);
            int off = h[h.length - 1] & 0x0f;
            int bin = ((h[off] & 0x7f) << 24) | ((h[off + 1] & 0xff) << 16)
                    | ((h[off + 2] & 0xff) << 8) | (h[off + 3] & 0xff);
            return bin % 1000000;
        } catch (Exception e) {
            throw new BizException("验证码计算失败");
        }
    }

    private String otpauthUri(User u, String secret) {
        String issuer = config("site_name", "LiteArk");
        String label = issuer + ":" + u.getUsername();
        return "otpauth://totp/" + url(label) + "?secret=" + secret + "&issuer=" + url(issuer)
                + "&algorithm=SHA1&digits=6&period=30";
    }

    private static String url(String s) {
        try { return java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20"); }
        catch (Exception e) { return s; }
    }

    // ===== 加密与哈希 =====

    private byte[] masterKey() {
        String s = props.getTotpMasterKey();
        if (s == null || s.trim().isEmpty()) s = props.getJwtSecret() + "|liteark-totp-master";
        try { return MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }

    private String encrypt(String plain) {
        try {
            byte[] iv = new byte[12];
            RANDOM.nextBytes(iv);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(masterKey(), "AES"), new GCMParameterSpec(128, iv));
            byte[] ct = c.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            byte[] out = Arrays.copyOf(iv, iv.length + ct.length);
            System.arraycopy(ct, 0, out, iv.length, ct.length);
            return Base64.getEncoder().encodeToString(out);
        } catch (Exception e) {
            throw new IllegalStateException("密钥加密失败", e);
        }
    }

    private String decrypt(String cipher) {
        try {
            byte[] all = Base64.getDecoder().decode(cipher);
            byte[] iv = Arrays.copyOf(all, 12);
            byte[] ct = Arrays.copyOfRange(all, 12, all.length);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(masterKey(), "AES"), new GCMParameterSpec(128, iv));
            return new String(c.doFinal(ct), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new BizException("二次验证密钥不可用，请联系管理员重置");
        }
    }

    private String hash(String prefix, String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(masterKey(), "HmacSHA256"));
            return Hex.encodeHexString(mac.doFinal((prefix + ":" + value).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private String deviceHash(Long userId, HttpServletRequest request) {
        String id = request.getHeader("X-Device-Id");
        if (id == null || id.trim().isEmpty()) {
            id = ClientUtil.ip(request) + "|" + ClientUtil.ua(request) + "|" + ClientUtil.clientType(request);
        }
        return hash("device", userId + ":" + id.trim());
    }

    private static String normalizeCode(String code) {
        return code == null ? "" : code.trim().toUpperCase().replace("-", "");
    }

    private static String now() {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());
    }

    private static String fmt(long millis) {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date(millis));
    }
}
