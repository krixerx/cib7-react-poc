package com.poc.cib7.documents;

import com.poc.cib7.ReservedBeansPlugin;
import freemarker.cache.ClassTemplateLoader;
import freemarker.core.TemplateClassResolver;
import freemarker.template.Configuration;
import freemarker.template.TemplateException;
import freemarker.template.TemplateExceptionHandler;
import java.io.IOException;
import java.io.StringWriter;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Pattern;
import org.cibseven.bpm.engine.delegate.VariableScope;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

/**
 * Renders the service pack's hand-designed documents ({@code documents/pdf/*.ftlh} for PDFs, {@code
 * documents/email/*.ftl} for email bodies) for the connector payload templates, which call {@code
 * ${documents.html("certificate", execution)}} or {@code ${documents.text(...)}} and only wrap the
 * result in the payload JSON.
 *
 * <p>Needed because the engine's own FreeMarker script engine has no template loader, so a payload
 * template cannot {@code <#include>} anything; layout and payload used to be one file per document,
 * with the brand repeated in each. Here documents share the pack's {@code _brand.ftl} and see
 * {@code brand} (see {@link DocumentBrand}).
 *
 * <p>A document sees the case's variables, {@code execution}, the reserved beans {@code
 * frontendBaseUrl} and {@code links} (which win over variables of the same name, as in BPMN), and
 * {@code brand}. {@code .ftlh} documents escape every value for HTML by default; {@code ?new} and
 * {@code ?api} are off.
 */
@Component("documents")
public class DocumentRenderer {

  private static final Pattern NAME = Pattern.compile("^[a-z0-9][a-z0-9-]{0,62}$");

  /** The reserved beans a document may use; the bus URL and the PDF helper stay out. */
  private static final String[] BEANS = {"frontendBaseUrl", "links"};

  private final Configuration configuration;
  private final Function<String, Object> beans;
  private final Map<String, Object> brand;

  @Autowired
  public DocumentRenderer(ApplicationContext applicationContext) {
    this(
        name -> applicationContext.containsBean(name) ? applicationContext.getBean(name) : null,
        "documents",
        "branding");
  }

  /** For tests: beans by name, and the classpath folders of the documents and the branding. */
  public DocumentRenderer(Function<String, Object> beans, String documentsRoot, String brandRoot) {
    this.beans = beans;
    // Read once: the pack is part of the image, so the brand cannot change while running.
    this.brand = java.util.Collections.unmodifiableMap(new DocumentBrand(brandRoot).model());
    Configuration c = new Configuration(Configuration.VERSION_2_3_35);
    c.setTemplateLoader(new ClassTemplateLoader(DocumentRenderer.class, "/" + documentsRoot));
    c.setDefaultEncoding("UTF-8");
    // Deterministic output: years and counts print as 2019, not 2,019; amounts use
    // ?string("0.00") explicitly.
    c.setLocale(Locale.US);
    c.setNumberFormat("computer");
    c.setRecognizeStandardFileExtensions(true);
    c.setNewBuiltinClassResolver(TemplateClassResolver.ALLOWS_NOTHING_RESOLVER);
    c.setAPIBuiltinEnabled(false);
    c.setTemplateExceptionHandler(TemplateExceptionHandler.RETHROW_HANDLER);
    c.setLogTemplateExceptions(false);
    c.setWrapUncheckedExceptions(true);
    c.setFallbackOnNullLoopVariable(false);
    this.configuration = c;
  }

  /** The PDF document {@code documents/pdf/<name>.ftlh}, as HTML. */
  public String html(String name, VariableScope execution) {
    return render("pdf/" + checked(name) + ".ftlh", execution);
  }

  /** The email body {@code documents/email/<name>.ftl}, as plain text. */
  public String text(String name, VariableScope execution) {
    return render("email/" + checked(name) + ".ftl", execution);
  }

  private static String checked(String name) {
    if (name == null || !NAME.matcher(name).matches()) {
      throw new IllegalArgumentException("Document name must be lowercase-kebab: " + name);
    }
    return name;
  }

  private String render(String path, VariableScope execution) {
    Map<String, Object> model = new HashMap<>(execution.getVariables());
    for (String name : BEANS) {
      Object bean = ReservedBeansPlugin.RESERVED_NAMES.contains(name) ? beans.apply(name) : null;
      if (bean != null) {
        model.put(name, bean);
      }
    }
    model.put("execution", execution);
    model.put("brand", brand);
    StringWriter out = new StringWriter();
    try {
      configuration.getTemplate(path).process(model, out);
    } catch (IOException | TemplateException e) {
      throw new IllegalStateException("Document " + path + " failed to render", e);
    }
    return out.toString();
  }
}
