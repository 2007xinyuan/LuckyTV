package com.github.catvod.crawler;

import com.github.catvod.crawler.python.IPyLoader;

import java.util.Map;

public class pyLoader implements IPyLoader {
    @Override
    public void clear() {
    }

    @Override
    public void setConfig(String jsonStr) {
    }

    @Override
    public void setRecentPyKey(String pyApi) {
    }

    @Override
    public Spider getSpider(String key, String cls, String ext) {
        return new SpiderNull();
    }

    @Override
    public Object[] proxyInvoke(Map<String, String> params) {
        return null;
    }
}
