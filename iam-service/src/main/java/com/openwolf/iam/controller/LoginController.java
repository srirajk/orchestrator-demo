package com.openwolf.iam.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Serves the Axiom-branded OIDC login page.
 *
 * Spring Security's formLogin() redirects unauthenticated OIDC flows to /login.
 * The Thymeleaf template at templates/login.html handles CSRF token injection
 * and renders the Axiom brand identity.
 */
@Controller
public class LoginController {

    @GetMapping("/login")
    public String loginPage() {
        return "login";
    }
}
