package com.poc.cib7;

import java.beans.FeatureDescriptor;
import java.util.AbstractMap;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.cibseven.bpm.engine.delegate.VariableScope;
import org.cibseven.bpm.engine.impl.cfg.AbstractProcessEnginePlugin;
import org.cibseven.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.cibseven.bpm.engine.impl.scripting.engine.Resolver;
import org.cibseven.bpm.engine.impl.scripting.engine.ResolverFactory;
import org.cibseven.bpm.engine.spring.SpringExpressionManager;
import org.cibseven.bpm.impl.juel.jakarta.el.CompositeELResolver;
import org.cibseven.bpm.impl.juel.jakarta.el.ELContext;
import org.cibseven.bpm.impl.juel.jakarta.el.ELResolver;
import org.cibseven.bpm.impl.juel.jakarta.el.PropertyNotWritableException;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

/**
 * Limits the Spring beans that BPMN expressions and FreeMarker templates can reach to {@link
 * #RESERVED_NAMES}, and makes those beans win over process variables of the same name.
 *
 * <p>Out of the box the Spring engine resolves every bean of the application context by name, so a
 * pack's connector input could read {@code ${environment.getProperty('INTERNAL_TASK_TOKEN')}} or
 * any secret a bean holds and post it to a host of its choosing ({@code BeanExposureTest} shows it
 * did on CIB seven 2.2). Setting the engine's {@code beans} map makes the Spring expression manager
 * use that map instead of the application context, and stops the Spring configuration from adding
 * its application-context script resolver; the map here holds the reserved names only and looks
 * each bean up when it is first read.
 *
 * <p>Out of the box the engine's JUEL resolver chain asks the variable scope before the Spring
 * context, so a client that writes a variable called {@code patchbayBaseUrl} or {@code
 * backendBaseUrl} would redirect every connector of that case to a host of its choosing. This
 * plugin puts a resolver for {@link #RESERVED_NAMES} at the front of the JUEL chain, and appends a
 * matching resolver to the script bindings, where the last resolver that knows a key wins.
 *
 * <p>Add a name here whenever a new bean is referenced from BPMN or a template.
 */
@Component
public class ReservedBeansPlugin extends AbstractProcessEnginePlugin {

  /** Spring bean names that a process variable may never shadow. */
  public static final Set<String> RESERVED_NAMES =
      Set.of("patchbayBaseUrl", "backendBaseUrl", "frontendBaseUrl", "pdf", "links", "documents");

  private final ApplicationContext applicationContext;

  public ReservedBeansPlugin(ApplicationContext applicationContext) {
    this.applicationContext = applicationContext;
  }

  /**
   * Replaces the Spring expression manager the factory bean installed just before engine build.
   * Functions (Spin's {@code S()}, the engine's own) are registered after {@code preInit}, so they
   * land on this instance.
   */
  @Override
  public void preInit(ProcessEngineConfigurationImpl configuration) {
    configuration.setBeans(new ReservedBeansMap(applicationContext));
    configuration.setExpressionManager(
        new ReservedFirstExpressionManager(applicationContext, configuration.getBeans()));
  }

  @Override
  public void postInit(ProcessEngineConfigurationImpl configuration) {
    List<ResolverFactory> factories =
        configuration.getScriptingEngines().getScriptBindingsFactory().getResolverFactories();
    factories.add(new ReservedBeansResolverFactory(applicationContext));
  }

  /**
   * The reserved beans as a read-only map, looked up in the application context on read so that
   * building the engine does not create them early.
   */
  static final class ReservedBeansMap extends AbstractMap<Object, Object> {

    private final ApplicationContext applicationContext;

    ReservedBeansMap(ApplicationContext applicationContext) {
      this.applicationContext = applicationContext;
    }

    @Override
    public boolean containsKey(Object key) {
      return key instanceof String name && RESERVED_NAMES.contains(name);
    }

    @Override
    public Object get(Object key) {
      return containsKey(key) ? applicationContext.getBean((String) key) : null;
    }

    @Override
    public Set<Object> keySet() {
      return Set.<Object>copyOf(RESERVED_NAMES);
    }

    @Override
    public Set<Entry<Object, Object>> entrySet() {
      Map<Object, Object> beans = new HashMap<>();
      RESERVED_NAMES.forEach(name -> beans.put(name, applicationContext.getBean(name)));
      return Set.copyOf(beans.entrySet());
    }
  }

  static final class ReservedFirstExpressionManager extends SpringExpressionManager {

    ReservedFirstExpressionManager(
        ApplicationContext applicationContext, Map<Object, Object> beans) {
      super(applicationContext, beans);
    }

    @Override
    protected ELResolver createElResolver() {
      CompositeELResolver resolver = new CompositeELResolver();
      resolver.add(new ReservedBeansElResolver(applicationContext));
      resolver.add(super.createElResolver());
      return resolver;
    }
  }

  static final class ReservedBeansElResolver extends ELResolver {

    private final ApplicationContext applicationContext;

    ReservedBeansElResolver(ApplicationContext applicationContext) {
      this.applicationContext = applicationContext;
    }

    private static boolean reserved(Object base, Object property) {
      return base == null && property instanceof String name && RESERVED_NAMES.contains(name);
    }

    @Override
    public Object getValue(ELContext context, Object base, Object property) {
      if (!reserved(base, property)) {
        return null;
      }
      context.setPropertyResolved(true);
      return applicationContext.getBean((String) property);
    }

    @Override
    public Class<?> getType(ELContext context, Object base, Object property) {
      if (!reserved(base, property)) {
        return null;
      }
      context.setPropertyResolved(true);
      return applicationContext.getType((String) property);
    }

    @Override
    public void setValue(ELContext context, Object base, Object property, Object value) {
      if (reserved(base, property)) {
        throw new PropertyNotWritableException("'" + property + "' is a reserved name");
      }
    }

    @Override
    public boolean isReadOnly(ELContext context, Object base, Object property) {
      if (!reserved(base, property)) {
        return false;
      }
      context.setPropertyResolved(true);
      return true;
    }

    @Override
    public Iterator<FeatureDescriptor> getFeatureDescriptors(ELContext context, Object base) {
      return null;
    }

    @Override
    public Class<?> getCommonPropertyType(ELContext context, Object base) {
      return base == null ? Object.class : null;
    }
  }

  static final class ReservedBeansResolverFactory implements ResolverFactory, Resolver {

    private final ApplicationContext applicationContext;

    ReservedBeansResolverFactory(ApplicationContext applicationContext) {
      this.applicationContext = applicationContext;
    }

    @Override
    public Resolver createResolver(VariableScope variableScope) {
      return this;
    }

    @Override
    public boolean containsKey(Object key) {
      return key instanceof String name && RESERVED_NAMES.contains(name);
    }

    @Override
    public Object get(Object key) {
      return containsKey(key) ? applicationContext.getBean((String) key) : null;
    }

    @Override
    public Set<String> keySet() {
      return RESERVED_NAMES;
    }
  }
}
