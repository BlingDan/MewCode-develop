package com.mewcode.config;

import java.util.ArrayList;
import java.util.List;

/** Root YAML configuration. */
public final class AppConfig {

    private List<ProviderConfig> providers = new ArrayList<>();

    public List<ProviderConfig> getProviders() {
        return providers;
    }

    public void setProviders(List<ProviderConfig> providers) {
        this.providers = providers == null ? new ArrayList<>() : providers;
    }
}
