package com.liteark.controller;

import com.liteark.entity.OperationLog;
import com.liteark.entity.User;
import com.liteark.mapper.OperationLogMapper;
import com.liteark.security.CurrentUser;
import com.liteark.service.AuditService;
import com.liteark.util.ClientUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;

import javax.servlet.http.HttpServletRequest;

@RequiredArgsConstructor
public abstract class BaseController {

    @Autowired
    protected CurrentUser currentUser;
    @Autowired
    protected OperationLogMapper logMapper;
    @Autowired
    protected HttpServletRequest request;
    /** 把内部 ID / 权限位翻成业务语言，供 audit 的 resource、detail 使用 */
    @Autowired
    protected AuditService auditService;

    protected void audit(String action, String resource, String detail) {
        audit(action, resource, detail, "success");
    }

    protected void audit(String action, String resource, String detail, String result) {
        User u = null;
        try { u = currentUser.get(); } catch (Exception ignored) {}
        writeLog(u, action, resource, detail, result);
    }

    /**
     * 登录、注册这类接口执行时 JWT 尚未生效，{@link #currentUser} 取不到人，
     * 需要把操作人显式传进来，否则日志的「操作人」列是空的。
     */
    protected void auditFor(User u, String action, String resource, String detail) {
        writeLog(u, action, resource, detail, "success");
    }

    protected void auditFor(User u, String action, String resource, String detail, String result) {
        writeLog(u, action, resource, detail, result);
    }

    private void writeLog(User u, String action, String resource, String detail, String result) {
        try {
            OperationLog log = new OperationLog();
            if (u != null) {
                log.setUserId(u.getId());
                log.setUsername(u.getUsername());
            }
            log.setAction(action);
            log.setResource(resource == null ? "" : resource);
            log.setDetail(detail == null ? "" : detail);
            log.setIp(ClientUtil.ip(request));
            log.setUa(ClientUtil.ua(request));
            log.setClientType(ClientUtil.clientType(request));
            log.setResult(result);
            logMapper.insert(log);
        } catch (Exception e) {
            // 审计失败不影响主流程
        }
    }
}
