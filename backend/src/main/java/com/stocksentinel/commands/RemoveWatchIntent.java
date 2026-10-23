package com.stocksentinel.commands;

import java.text.Normalizer;
import java.util.regex.Pattern;

/** Destructive tools require a complete explicit single-stock command in this message. */
public final class RemoveWatchIntent {
    private RemoveWatchIntent() {}
    public static boolean explicit(String message,String stockQuery) {
        if(message==null||stockQuery==null||stockQuery.isBlank())return false;
        String text=normalize(message.replaceFirst("^\\s*@[\\p{L}\\p{N}_-]{1,40}[\\s\\p{Z}]+","")),stock=Pattern.quote(normalize(stockQuery));
        String action="(?:取消关注|删除关注|移除关注|取消监控|停止监控|不再关注|不再监控)";
        String prefix="(?:请|帮我|麻烦|麻烦你|请帮我|我要|我想)?(?:把|将)?";
        String suffix="(?:一下|吧|了)?[。！!]?";
        return text.matches(prefix+action+stock+suffix)||text.matches(prefix+stock+action+suffix);
    }
    private static String normalize(String text){return Normalizer.normalize(text,Normalizer.Form.NFKC).replaceAll("\\s+","");}
}
