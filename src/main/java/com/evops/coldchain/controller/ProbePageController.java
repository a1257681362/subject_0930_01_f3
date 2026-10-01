package com.evops.coldchain.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * 车载温度探头校准凭证管理页面（Thymeleaf）。
 * 页面数据经 /api/cold-chain/probes 同源拉取（HTTP Basic 登录态与接口共用）。
 */
@Controller
@RequestMapping("/ui/cold-chain")
public class ProbePageController {

    @GetMapping("/probes")
    public String probesPage() {
        return "coldchain/probes";
    }
}
