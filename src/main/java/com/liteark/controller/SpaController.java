package com.liteark.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/** SPA 前端路由回退：非 API 的 GET 请求统一返回 index.html */
@Controller
public class SpaController {

    @GetMapping(value = {
            "/", "/login", "/totp", "/files", "/shares", "/recycle", "/profile",
            "/admin", "/admin/**", "/s/**", "/recent"
    })
    public String index() {
        return "forward:/index.html";
    }
}
