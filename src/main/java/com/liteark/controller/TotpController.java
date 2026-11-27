package com.liteark.controller;

import com.liteark.common.BizException;
import com.liteark.common.R;
import com.liteark.entity.Role;
import com.liteark.entity.TrustedDevice;
import com.liteark.entity.User;
import com.liteark.mapper.RoleMapper;
import com.liteark.mapper.UserMapper;
import com.liteark.security.CurrentUser;
import com.liteark.service.TotpService;
import com.liteark.util.ClientUtil;
import com.liteark.util.JwtUtil;
import io.jsonwebtoken.Claims;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/auth/totp")
@RequiredArgsConstructor
public class TotpController extends BaseController {
    private final UserMapper userMapper;
    private final RoleMapper roleMapper;
    private final CurrentUser current;
    private final JwtUtil jwtUtil;
    private final TotpService totpService;

    @Data
    public static class CodeReq {
        private String code;
        private Boolean trustDevice;
    }

    @Data
    public static class VerifyReq {
        private String code;
        private String recoveryCode;
    }

    @Data
    public static class VerifyLoginReq {
        private String pendingToken;
        private String code;
        private Boolean trustDevice;
    }

    @Data
    public static class RecoveryLoginReq {
        private String pendingToken;
        private String recoveryCode;
    }

    @GetMapping("/status")
    public R<Map<String, Object>> status() {
        User u = current.get();
        auditExpired(u);
        return R.ok(totpService.status(u));
    }

    @PostMapping("/bind")
    public R<Map<String, Object>> bind() {
        User u = current.get();
        requireManageable(u);
        Map<String, Object> r = totpService.beginBind(u);
        auditFor(u, "发起二次验证绑定", "账号：" + u.getUsername(), "已生成动态验证码密钥，等待验证器确认");
        return R.ok(r);
    }

    @PostMapping("/bind/verify")
    public R<Map<String, Object>> verifyBind(@RequestBody CodeReq req) {
        User u = current.get();
        requireManageable(u);
        try {
            List<String> codes = totpService.confirmBind(u, req.getCode());
            auditFor(u, "完成二次验证绑定", "账号：" + u.getUsername(),
                    "验证器校验通过，已生成 " + codes.size() + " 个备用恢复码");
            Map<String, Object> m = new HashMap<>();
            m.put("recoveryCodes", codes);
            return R.ok(m);
        } catch (BizException e) {
            auditFor(u, "绑定二次验证", "账号：" + u.getUsername(), "动态验证码校验未通过", "fail");
            throw e;
        }
    }

    @PostMapping("/unbind")
    public R<Void> unbind(@RequestBody VerifyReq req) {
        User u = current.get();
        requireManageable(u);
        try {
            totpService.unbind(u, req.getCode(), req.getRecoveryCode());
            auditFor(u, "关闭二次验证", "账号：" + u.getUsername(), "已清除密钥、恢复码与全部信任设备");
            return R.ok();
        } catch (BizException e) {
            auditFor(u, "关闭二次验证", "账号：" + u.getUsername(), "身份核验未通过", "fail");
            throw e;
        }
    }

    @PostMapping("/recovery/regenerate")
    public R<Map<String, Object>> regenerate(@RequestBody VerifyReq req) {
        User u = current.get();
        requireManageable(u);
        try {
            List<String> codes = totpService.regenerateRecovery(u, req.getCode(), req.getRecoveryCode());
            auditFor(u, "重新生成恢复码", "账号：" + u.getUsername(),
                    "旧批次恢复码已作废，生成 " + codes.size() + " 个新恢复码");
            Map<String, Object> m = new HashMap<>();
            m.put("recoveryCodes", codes);
            return R.ok(m);
        } catch (BizException e) {
            auditFor(u, "重新生成恢复码", "账号：" + u.getUsername(), "身份核验未通过", "fail");
            throw e;
        }
    }

    @GetMapping("/devices")
    public R<List<TrustedDevice>> devices() {
        User u = current.get();
        auditExpired(u);
        return R.ok(totpService.devices(u.getId()));
    }

    @DeleteMapping("/devices/{id}")
    public R<Void> deleteDevice(@PathVariable Long id) {
        User u = current.get();
        requireManageable(u);
        TrustedDevice d = totpService.device(u.getId(), id);
        if (d == null) throw new BizException(404, "信任设备不存在");
        totpService.deleteDevice(u.getId(), id);
        auditFor(u, "删除信任设备", "账号：" + u.getUsername(), "设备：" + deviceText(d));
        return R.ok();
    }

    // ===== 登录二次验证（临时令牌，非登录态） =====

    @PostMapping("/verify-login")
    public R<Map<String, Object>> verifyLogin(@RequestBody VerifyLoginReq req) {
        User u = pendingUser(req.getPendingToken());
        try {
            totpService.verifyCode(u, req.getCode());
            if (Boolean.TRUE.equals(req.getTrustDevice())) {
                totpService.trustDevice(u.getId(), request);
                auditFor(u, "新增信任设备", "账号：" + u.getUsername(),
                        "设备：" + totpService.deviceName(request) + "，本次登录已免验证");
            }
            return R.ok(finishLogin(u, "动态验证码校验通过"));
        } catch (BizException e) {
            auditFor(u, "登录二次验证失败", "账号：" + u.getUsername(), "动态验证码校验未通过", "fail");
            throw e;
        }
    }

    @PostMapping("/recovery-login")
    public R<Map<String, Object>> recoveryLogin(@RequestBody RecoveryLoginReq req) {
        User u = pendingUser(req.getPendingToken());
        try {
            totpService.consumeRecoveryCode(u, req.getRecoveryCode());
            return R.ok(finishLogin(u, "使用备用恢复码完成二次验证"));
        } catch (BizException e) {
            auditFor(u, "登录二次验证失败", "账号：" + u.getUsername(), "备用恢复码校验未通过", "fail");
            throw e;
        }
    }

    // ===== 内部方法 =====

    private User pendingUser(String token) {
        Claims c = jwtUtil.parsePending(token);
        if (c == null) throw new BizException(401, "二次验证已过期，请重新登录");
        User u = userMapper.selectById(Long.valueOf(c.getSubject()));
        if (u == null || u.getStatus() != null && u.getStatus() != 1) {
            throw new BizException("账号不可用，请重新登录");
        }
        return u;
    }

    private Map<String, Object> finishLogin(User u, String detail) {
        u.setLastLogin(new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new java.util.Date()));
        userMapper.updateById(u);
        auditFor(u, "登录", "账号：" + u.getUsername(),
                detail + "，" + com.liteark.service.AuditService.client(ClientUtil.clientType(request)) + "登录");
        return tokenPayload(u);
    }

    private Map<String, Object> tokenPayload(User u) {
        Role role = roleMapper.selectById(u.getRoleId());
        Map<String, Object> m = new HashMap<>();
        m.put("token", jwtUtil.generate(u.getId(), u.getUsername()));
        Map<String, Object> um = new HashMap<>();
        um.put("id", u.getId());
        um.put("username", u.getUsername());
        um.put("nickname", u.getNickname());
        um.put("role_id", u.getRoleId());
        um.put("role_name", role == null ? "" : role.getName());
        um.put("quota", u.getQuota());
        um.put("used", u.getUsed() == null ? 0 : u.getUsed());
        m.put("user", um);
        return m;
    }

    private void requireManageable(User u) {
        if (!totpService.globalEnabled() || !totpService.allowed(u)) {
            throw new BizException("当前账号不可管理二次验证");
        }
    }

    private void auditExpired(User u) {
        int n = totpService.purgeExpiredDevices(u.getId());
        if (n > 0) auditFor(u, "信任设备过期", "账号：" + u.getUsername(), "已自动清理 " + n + " 条过期信任设备");
    }

    private static String deviceText(TrustedDevice d) {
        return d.getDeviceName() == null || d.getDeviceName().isEmpty() ? "未命名设备" : d.getDeviceName();
    }
}
