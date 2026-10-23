package com.stocksentinel.custom;
import org.springframework.stereotype.Controller;import org.springframework.web.bind.annotation.GetMapping;
@Controller public class ManagementPageController {
    @GetMapping({"/manage","/manage/"})public String page(){return "forward:/manage/index.html";}
}
