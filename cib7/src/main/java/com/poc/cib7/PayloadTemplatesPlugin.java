package com.poc.cib7;

import freemarker.core.TemplateClassResolver;
import freemarker.template.Configuration;
import javax.script.ScriptEngine;
import javax.script.ScriptEngineManager;
import org.cibseven.bpm.engine.impl.cfg.AbstractProcessEnginePlugin;
import org.cibseven.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.cibseven.templateengines.FreeMarkerScriptEngine;
import org.cibseven.templateengines.FreeMarkerScriptEngineFactory;
import org.springframework.stereotype.Component;

/**
 * Runs the engine's FreeMarker scripts (the payload templates behind {@code camunda:script
 * scriptFormat="freemarker"}) with {@code ?new} and {@code ?api} refused, the settings {@code
 * DocumentRenderer} already uses for the hand-designed documents (design D17 of
 * replace-esb-with-patchbay).
 *
 * <p>The stock {@code cibseven-template-engines-freemarker} engine builds its own {@code
 * Configuration} lazily in {@code initConfiguration()} with FreeMarker's defaults, where {@code
 * ?new} instantiates any {@code TemplateModel} class, {@code ObjectConstructor} included; it offers
 * no setter or hook. So this plugin registers a factory whose engine sets that configuration in its
 * constructor, under every name the stock factory answers to. A name registered on the engine's
 * {@code ScriptEngineManager} wins over the factory found on the classpath, and {@code postInit}
 * runs before any script engine is looked up and cached.
 */
@Component
public class PayloadTemplatesPlugin extends AbstractProcessEnginePlugin {

  @Override
  public void postInit(ProcessEngineConfigurationImpl configuration) {
    ScriptEngineManager manager = configuration.getScriptingEngines().getScriptEngineManager();
    RestrictedFreeMarkerFactory factory = new RestrictedFreeMarkerFactory();
    for (String name : factory.getNames()) {
      manager.registerEngineName(name, factory);
    }
  }

  /** The FreeMarker configuration of every payload template. */
  static Configuration restrictedConfiguration() {
    // The version the stock engine uses, so that only the two settings below change the output.
    Configuration c = new Configuration(Configuration.VERSION_2_3_29);
    c.setNewBuiltinClassResolver(TemplateClassResolver.ALLOWS_NOTHING_RESOLVER);
    c.setAPIBuiltinEnabled(false);
    return c;
  }

  static final class RestrictedFreeMarkerFactory extends FreeMarkerScriptEngineFactory {
    @Override
    public ScriptEngine getScriptEngine() {
      return new RestrictedFreeMarkerEngine(this);
    }
  }

  static final class RestrictedFreeMarkerEngine extends FreeMarkerScriptEngine {
    RestrictedFreeMarkerEngine(RestrictedFreeMarkerFactory factory) {
      super(factory);
      // Set before first use, so the stock initConfiguration() finds it and keeps it.
      this.configuration = restrictedConfiguration();
    }
  }
}
