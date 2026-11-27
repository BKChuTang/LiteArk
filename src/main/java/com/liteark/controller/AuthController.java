package com.liteark.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.liteark.common.BizException;
import com.liteark.common.R;
import com.liteark.config.LiteArkProperties;
import com.liteark.entity.Role;
import com.liteark.entity.User;
import com.liteark.mapper.RoleMapper;
import com.liteark.mapper.UserMapper;
import com.liteark.security.CurrentUser;
import com.liteark.service.TotpService;
import com.liteark.util.ClientUtil;
import com.liteark.util.JwtUtil;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController extends BaseController {
    private final UserMapper userMapper;
    private final RoleMapper roleMapper;
    private final BCryptPasswordEncoder encoder;
    private final JwtUtil jwtUtil;
    private final LiteArkProperties props;
    private final CurrentUser current;
    private final TotpService totpService;

    @Data
    public static class LoginReq {
        private String username;
        private String password;
    }

    @Data
    public static class RegisterReq {
        private String username;
        private String password;
        private String nickname;
    }

    @Data
    public static class PwdReq {
        private String oldPassword;
        private String newPassword;
    }

    @PostMapping("/login")
    public R<Map<String, Object>> login(@RequestBody LoginReq req) {
        if (req.getUsername() == null || req.getPassword() == null) throw new BizException("账号和密码不能为空");
        User u = userMapper.selectOne(new LambdaQueryWrapper<User>().eq(User::getUsername, req.getUsername()));
        if (u == null) {
            auditFor(null, "登录失败", "账号：" + req.getUsername(), "该账号不存在", "fail");
            throw new BizException("账号或密码错误");
        }
        if (u.getStatus() != null && u.getStatus() != 1) throw new BizException("账号已被禁用，请联系管理员");
        if (!encoder.matches(req.getPassword(), u.getPassword())) {
            auditFor(u, "登录失败", "账号：" + req.getUsername(), "密码不正确", "fail");
            throw new BizException("账号或密码错误");
        }
        if (totpService.isEnabled(u.getId())) {
            int expired = totpService.purgeExpiredDevices(u.getId());
            if (expired > 0) {
                auditFor(u, "信任设备过期", "账号：" + u.getUsername(), "已自动清理 " + expired + " 条过期信任设备");
            }
            if (!totpService.isTrusted(u.getId(), request)) {
                Map<String, Object> pending = new HashMap<>();
                pending.put("needTotp", true);
                pending.put("pendingToken", jwtUtil.generatePending(u.getId(), u.getUsername()));
                pending.put("username", u.getUsername());
                pending.put("trustedDeviceTtlDays", totpService.configInt("trusted_device_ttl_days", 30));
                auditFor(u, "登录", "账号：" + u.getUsername(), "密码校验通过，待二次验证");
                return R.ok(pending);
            }
        }
        return R.ok(finishLogin(u));
    }

    @PostMapping("/register")
    public R<Map<String, Object>> register(@RequestBody RegisterReq req) {
        String username = req.getUsername();
        String password = req.getPassword();
        if (username == null || password == null) throw new BizException("账号和密码不能为空");
        if (username.length() < 3 || username.length() > 32) throw new BizException("账号长度 3-32 位");
        if (password.length() < 6) throw new BizException("密码至少 6 位");
        if (userMapper.selectCount(new LambdaQueryWrapper<User>().eq(User::getUsername, username)) > 0) {
            throw new BizException("账号已存在");
        }
        User u = new User();
        u.setUsername(username);
        u.setPassword(encoder.encode(password));
        u.setNickname(req.getNickname() == null ? username : req.getNickname());
        u.setRoleId(2L);
        u.setQuota(props.getDefaultQuota());
        u.setUsed(0L);
        u.setStatus(1);
        userMapper.insert(u);
        auditFor(u, "注册", "账号：" + username, "通过注册页新建并登录");
        return R.ok(tokenPayload(u));
    }

    @GetMapping("/me")
    public R<Map<String, Object>> me() {
        User u = current.get();
        Role role = roleMapper.selectById(u.getRoleId());
        Map<String, Object> m = new HashMap<>();
        m.put("id", u.getId());
        m.put("username", u.getUsername());
        m.put("nickname", u.getNickname());
        m.put("role_id", u.getRoleId());
        m.put("role_name", role == null ? "" : role.getName());
        m.put("quota", u.getQuota());
        m.put("used", u.getUsed() == null ? 0 : u.getUsed());
        m.put("totp_enabled", totpService.isEnabled(u.getId()));
        m.put("totp_allowed", totpService.allowed(u) ? 1 : 0);
        return R.ok(m);
    }

    @PutMapping("/password")
    public R<Void> changePassword(@RequestBody PwdReq req) {
        User u = current.get();
        if (req.getOldPassword() == null || req.getNewPassword() == null) throw new BizException("请填写完整");
        if (req.getNewPassword().length() < 6) throw new BizException("新密码至少 6 位");
        if (!encoder.matches(req.getOldPassword(), u.getPassword())) throw new BizException("原密码错误");
        u.setPassword(encoder.encode(req.getNewPassword()));
        userMapper.updateById(u);
        audit("修改密码", "账号：" + u.getUsername(), "登录密码已更新");
        return R.ok();
    }

    @PutMapping("/profile")
    public R<Void> updateProfile(@RequestBody Map<String, String> body) {
        User u = current.get();
        String nickname = body.get("nickname");
        if (nickname == null || nickname.trim().isEmpty()) throw new BizException("昵称不能为空");
        u.setNickname(nickname);
        userMapper.updateById(u);
        return R.ok();
    }

    private Map<String, Object> finishLogin(User u) {
        u.setLastLogin(now());
        userMapper.updateById(u);
        auditFor(u, "登录", "账号：" + u.getUsername(),
                com.liteark.service.AuditService.client(ClientUtil.clientType(request)) + "登录");
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

    private static String now() {
        return new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new java.util.Date());
    }
}
