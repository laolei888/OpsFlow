package com.opsflow.identity;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class SsoCallbackViewController {
    @GetMapping("/sso/callback")
    public String callback() {
        return "forward:/index.html";
    }
}
