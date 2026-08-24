package com.mewcode.config;

import java.util.ArrayList;
import java.util.List;

/** Root YAML configuration. */
public final class AppConfig {

    private List<ProviderConfig> providers = new ArrayList<>();
    private AgentConfig agent = new AgentConfig();

    public List<ProviderConfig> getProviders() {
        return providers;
    }

    public void setProviders(List<ProviderConfig> providers) {
        this.providers = providers == null ? new ArrayList<>() : providers;
    }

    public AgentConfig getAgent() {
        return agent;
    }

    public void setAgent(AgentConfig agent) {
        this.agent = agent == null ? new AgentConfig() : agent;
    }
}
