package com.mewcode.config;

import com.mewcode.permission.PathAuthorizationStore;
import com.mewcode.permission.PermissionMode;
import com.mewcode.permission.PermissionRule;
import com.mewcode.permission.PermissionRuleEngine;
import com.mewcode.permission.RuleSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.yaml.snakeyaml.Yaml;

/** 加载用户、项目、本地三层权限规则，并在配置错误时 Fail-Closed。 */
public final class PermissionConfigLoader {
  private PermissionConfigLoader() {}

  public static LoadedPermissions load(Path projectRoot, PermissionConfig config)
      throws ConfigLoader.ConfigException {
    PermissionMode mode;
    try {
      mode = PermissionMode.parse(config == null ? null : config.getMode());
    } catch (IllegalArgumentException error) {
      throw new ConfigLoader.ConfigException(
          "permissions.mode 无效，只能是 default、acceptEdits、plan 或 bypassPermissions");
    }

    var rules = new ArrayList<PermissionRule>();
    Path localFile = projectRoot.resolve(".mewcode/permissions.local.yaml");
    loadFile(localFile, RuleSource.LOCAL, rules);
    loadFile(projectRoot.resolve(".mewcode/permissions.yaml"), RuleSource.PROJECT, rules);
    loadFile(userFile(), RuleSource.USER, rules);
    PathAuthorizationStore store;
    try {
      store = PathAuthorizationStore.load(projectRoot);
    } catch (IOException error) {
      throw new ConfigLoader.ConfigException(error.getMessage());
    }
    return new LoadedPermissions(mode, new PermissionRuleEngine(rules), store);
  }

  private static void loadFile(Path file, RuleSource source, List<PermissionRule> output)
      throws ConfigLoader.ConfigException {
    if (!Files.exists(file)) return;
    if (!Files.isRegularFile(file)) {
      throw new ConfigLoader.ConfigException("权限文件不是普通文件：" + file);
    }
    Object parsed;
    try {
      parsed = new Yaml().load(Files.readString(file));
    } catch (IOException | RuntimeException error) {
      throw new ConfigLoader.ConfigException("权限文件无法读取或 YAML 无效：" + file);
    }
    if (parsed == null) return;
    if (!(parsed instanceof Map<?, ?> map)) {
      throw new ConfigLoader.ConfigException("权限文件必须是 YAML 对象：" + file);
    }
    Object rawRules = map.get("rules");
    if (rawRules == null) return;
    if (!(rawRules instanceof List<?> list)) {
      throw new ConfigLoader.ConfigException("权限文件 rules 必须是列表：" + file);
    }
    for (int index = 0; index < list.size(); index++) {
      Object item = list.get(index);
      if (!(item instanceof Map<?, ?> rule)) {
        throw new ConfigLoader.ConfigException("权限文件 rules[" + index + "] 必须是对象：" + file);
      }
      Object pattern = rule.get("pattern");
      Object tool = rule.get("tool");
      Object match = rule.get("match");
      Object decision = rule.get("decision");
      boolean hasLegacy = pattern != null;
      boolean hasStructured = tool != null || match != null;
      if (hasLegacy == hasStructured
          || !(decision instanceof String decisionText)
          || (hasLegacy && !(pattern instanceof String patternText))
          || (hasStructured && (!(tool instanceof String) || !(match instanceof Map<?, ?>)))) {
        throw new ConfigLoader.ConfigException(
            "权限文件 rules[" + index + "] 必须提供互斥的 pattern 或 tool+match 及 decision：" + file);
      }
      try {
        if (hasLegacy) {
          output.add(PermissionRule.of((String) pattern, decisionText, source));
        } else {
          output.add(PermissionRule.of((String) tool, (Map<?, ?>) match, decisionText, source));
        }
      } catch (IllegalArgumentException error) {
        throw new ConfigLoader.ConfigException("权限文件 rules[" + index + "] 无效：" + file);
      }
    }
  }

  private static Path userFile() {
    return Path.of(System.getProperty("user.home", "."), ".mewcode", "permissions.yaml")
        .toAbsolutePath()
        .normalize();
  }

  public record LoadedPermissions(
      PermissionMode mode,
      PermissionRuleEngine ruleEngine,
      PathAuthorizationStore pathAuthorizationStore) {}
}
