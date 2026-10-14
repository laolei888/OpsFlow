package com.opsflow.statuspage;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class StatusPageViewController {
    @GetMapping("/status")
    public String publicStatusPage() {
        return "forward:/index.html";
    }
}
