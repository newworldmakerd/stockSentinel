package com.stocksentinel.custom;
import jakarta.servlet.*;import jakarta.servlet.http.*;import org.springframework.web.filter.OncePerRequestFilter;import org.springframework.stereotype.Component;import java.io.IOException;import java.net.*;import java.util.*;
/** Only these new management routes are restricted; existing endpoints are unchanged. */
@Component
public class ManagementAccessFilter extends OncePerRequestFilter {
    @Override protected boolean shouldNotFilter(HttpServletRequest r){return !r.getRequestURI().startsWith("/api/v1/custom-strategies")&&!r.getRequestURI().equals("/manage")&&!r.getRequestURI().startsWith("/manage/");}
    @Override protected void doFilterInternal(HttpServletRequest r,HttpServletResponse response,FilterChain chain)throws ServletException,IOException{
        boolean allowed=false;try{allowed=Set.of("127.0.0.1","::1","0:0:0:0:0:0:0:1").contains(r.getRemoteAddr())&&Set.of("localhost","127.0.0.1","::1","[::1]").contains(r.getServerName());String origin=r.getHeader("Origin");if(origin!=null){URI u=URI.create(origin);int port=u.getPort()<0?(u.getScheme().equals("https")?443:80):u.getPort();allowed&=u.getScheme().equals(r.getScheme())&&u.getHost().equals(r.getServerName())&&port==r.getServerPort();}}catch(Exception invalid){allowed=false;}
        if(!allowed){response.setStatus(403);response.setContentType("application/json");response.getWriter().write("{\"error\":\"LOCAL_MANAGEMENT_ONLY\"}");return;}
        response.setHeader("Cache-Control","no-store");response.setHeader("X-Content-Type-Options","nosniff");response.setHeader("X-Frame-Options","DENY");response.setHeader("Content-Security-Policy","default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; connect-src 'self'; frame-ancestors 'none'");chain.doFilter(r,response);
    }
}
