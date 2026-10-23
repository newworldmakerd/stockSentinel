package com.stocksentinel.document;

import java.net.URI;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** The endpoint contains authorization material: never expose it via toString or diagnostics. */
@Component
public class DocumentSettings {
    public final boolean enabled,scheduleEnabled;
    private final URI endpoint;
    public DocumentSettings(@Value("${wecom-doc.enabled:false}") boolean enabled,
            @Value("${wecom-doc.schedule-enabled:false}") boolean scheduleEnabled,@Value("${wecom-doc.mcp-url:}") String url) {
        this.enabled=enabled;this.scheduleEnabled=scheduleEnabled;URI parsed=null;
        if(enabled) {
            try {parsed=URI.create(url);}catch(Exception invalid){throw new IllegalArgumentException("WECOM_DOCUMENT_ENDPOINT_REQUIRED");}
            String host=parsed.getHost();
            if(!"https".equalsIgnoreCase(parsed.getScheme())||host==null||!(host.equals("work.weixin.qq.com")||host.endsWith(".work.weixin.qq.com")||host.equals("qyapi.weixin.qq.com"))
                    ||parsed.getUserInfo()!=null||parsed.getFragment()!=null||parsed.getPort()>0&&parsed.getPort()!=443)
                throw new IllegalArgumentException("WECOM_DOCUMENT_OFFICIAL_HTTPS_ENDPOINT_REQUIRED");
        }
        this.endpoint=parsed;
    }
    URI endpoint(){return endpoint;}
}
