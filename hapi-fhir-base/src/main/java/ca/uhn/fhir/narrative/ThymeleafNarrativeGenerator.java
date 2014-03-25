package ca.uhn.fhir.narrative;

import static org.apache.commons.lang3.StringUtils.isBlank;

import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Properties;

import org.apache.commons.io.IOUtils;
import org.apache.commons.io.input.ReaderInputStream;
import org.thymeleaf.Arguments;
import org.thymeleaf.Configuration;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.TemplateProcessingParameters;
import org.thymeleaf.context.Context;
import org.thymeleaf.dom.Document;
import org.thymeleaf.dom.Element;
import org.thymeleaf.dom.Node;
import org.thymeleaf.processor.IProcessor;
import org.thymeleaf.processor.ProcessorResult;
import org.thymeleaf.processor.attr.AbstractAttrProcessor;
import org.thymeleaf.resourceresolver.IResourceResolver;
import org.thymeleaf.standard.StandardDialect;
import org.thymeleaf.standard.expression.IStandardExpression;
import org.thymeleaf.standard.expression.IStandardExpressionParser;
import org.thymeleaf.standard.expression.StandardExpressions;
import org.thymeleaf.templateresolver.TemplateResolver;
import org.thymeleaf.util.DOMUtils;

import ca.uhn.fhir.context.ConfigurationException;
import ca.uhn.fhir.model.api.IResource;
import ca.uhn.fhir.model.dstu.composite.NarrativeDt;
import ca.uhn.fhir.model.dstu.composite.QuantityDt;
import ca.uhn.fhir.model.dstu.valueset.NarrativeStatusEnum;
import ca.uhn.fhir.model.primitive.XhtmlDt;
import ca.uhn.fhir.parser.DataFormatException;

public class ThymeleafNarrativeGenerator implements INarrativeGenerator {
	private static final org.slf4j.Logger ourLog = org.slf4j.LoggerFactory.getLogger(ThymeleafNarrativeGenerator.class);

	private HashMap<String, String> myDatatypeClassNameToNarrativeTemplate;
	private TemplateEngine myDatatypeTemplateEngine;

	private boolean myIgnoreFailures = true;

	private boolean myIgnoreMissingTemplates = true;

	private TemplateEngine myProfileTemplateEngine;
	private HashMap<String, String> myProfileToNarrativeTemplate;

	public ThymeleafNarrativeGenerator() throws IOException {
		myProfileToNarrativeTemplate = new HashMap<String, String>();
		myDatatypeClassNameToNarrativeTemplate = new HashMap<String, String>();

		String propFileName = "classpath:ca/uhn/fhir/narrative/narratives.properties";
		loadProperties(propFileName);

		{
			myProfileTemplateEngine = new TemplateEngine();
			TemplateResolver resolver = new TemplateResolver();
			resolver.setResourceResolver(new ProfileResourceResolver());
			myProfileTemplateEngine.setTemplateResolver(resolver);
			StandardDialect dialect = new StandardDialect();
			HashSet<IProcessor> additionalProcessors = new HashSet<IProcessor>();
			additionalProcessors.add(new MyProcessor());
			dialect.setAdditionalProcessors(additionalProcessors);
			myProfileTemplateEngine.setDialect(dialect);
			myProfileTemplateEngine.initialize();
		}
		{
			myDatatypeTemplateEngine = new TemplateEngine();
			TemplateResolver resolver = new TemplateResolver();
			resolver.setResourceResolver(new DatatypeResourceResolver());
			myDatatypeTemplateEngine.setTemplateResolver(resolver);
			myDatatypeTemplateEngine.setDialect(new StandardDialect());
			myDatatypeTemplateEngine.initialize();
		}

	}

	@Override
	public NarrativeDt generateNarrative(String theProfile, IResource theResource) {
		if (myIgnoreMissingTemplates && !myProfileToNarrativeTemplate.containsKey(theProfile)) {
			ourLog.debug("No narrative template available for profile: {}", theProfile);
			return new NarrativeDt(new XhtmlDt("<div>No narrative available</div>"), NarrativeStatusEnum.EMPTY);
		}
		
		try {
			Context context = new Context();
			context.setVariable("resource", theResource);

			String result = myProfileTemplateEngine.process(theProfile, context);
			result = result.replaceAll("\\s+", " ").replace("> ", ">").replace(" <", "<");

			XhtmlDt div = new XhtmlDt(result);
			return new NarrativeDt(div, NarrativeStatusEnum.GENERATED);
		} catch (Exception e) {
			if (myIgnoreFailures) {
				ourLog.error("Failed to generate narrative", e);
				return new NarrativeDt(new XhtmlDt("<div>No narrative available</div>"), NarrativeStatusEnum.EMPTY);
			} else {
				throw new DataFormatException(e);
			}
		}
	}

	/**
	 * If set to <code>true</code>, which is the default, if any failure occurs during narrative generation the generator will suppress any generated exceptions, and simply return a default narrative
	 * indicating that no narrative is available.
	 */
	public boolean isIgnoreFailures() {
		return myIgnoreFailures;
	}

	/**
	 * If set to true, will return an empty narrative block for any
	 * profiles where no template is available
	 */
	public boolean isIgnoreMissingTemplates() {
		return myIgnoreMissingTemplates;
	}

	private void loadProperties(String propFileName) throws IOException {
		Properties file = new Properties();

		InputStream resource = loadResource(propFileName);
		file.load(resource);
		for (Object nextKeyObj : file.keySet()) {
			String nextKey = (String) nextKeyObj;
			if (nextKey.endsWith(".profile")) {
				String name = nextKey.substring(0, nextKey.indexOf(".profile"));
				if (isBlank(name)) {
					continue;
				}

				String narrativeName = file.getProperty(name + ".narrative");
				if (isBlank(narrativeName)) {
					continue;
				}

				String narrative = IOUtils.toString(loadResource(narrativeName));
				myProfileToNarrativeTemplate.put(file.getProperty(nextKey), narrative);
			} else if (nextKey.endsWith(".dtclass")) {
				String name = nextKey.substring(0, nextKey.indexOf(".dtclass"));
				if (isBlank(name)) {
					continue;
				}

				String className = file.getProperty(nextKey);

				Class<?> dtClass;
				try {
					dtClass = Class.forName(className);
				} catch (ClassNotFoundException e) {
					ourLog.warn("Unknown datatype class '{}' identified in narrative file {}", name, propFileName);
					continue;
				}

				String narrativeName = file.getProperty(name + ".dtnarrative");
				if (isBlank(narrativeName)) {
					continue;
				}

				String narrative = IOUtils.toString(loadResource(narrativeName));
				myDatatypeClassNameToNarrativeTemplate.put(dtClass.getCanonicalName(), narrative);
			}

		}
	}

	private InputStream loadResource(String name) {
		if (name.startsWith("classpath:")) {
			String cpName = name.substring("classpath:".length());
			InputStream resource = ThymeleafNarrativeGenerator.class.getResourceAsStream(cpName);
			if (resource == null) {
				resource = ThymeleafNarrativeGenerator.class.getResourceAsStream("/" + cpName);
				if (resource == null) {
					throw new ConfigurationException("Can not find '" + cpName + "' on classpath");
				}
			}
			return resource;
		} else {
			throw new IllegalArgumentException("Invalid resource name: '" + name + "'");
		}
	}

	/**
	 * If set to <code>true</code>, which is the default, if any failure occurs during narrative generation the generator will suppress any generated exceptions, and simply return a default narrative
	 * indicating that no narrative is available.
	 */
	public void setIgnoreFailures(boolean theIgnoreFailures) {
		myIgnoreFailures = theIgnoreFailures;
	}

	// public String generateString(Patient theValue) {
	//
	// Context context = new Context();
	// context.setVariable("resource", theValue);
	// String result = myProfileTemplateEngine.process("ca/uhn/fhir/narrative/Patient.html", context);
	//
	// ourLog.info("Result: {}", result);
	//
	// return result;
	// }

	/**
	 * If set to true, will return an empty narrative block for any
	 * profiles where no template is available
	 */
	public void setIgnoreMissingTemplates(boolean theIgnoreMissingTemplates) {
		myIgnoreMissingTemplates = theIgnoreMissingTemplates;
	}

	private final class DatatypeResourceResolver implements IResourceResolver {
		@Override
		public String getName() {
			return getClass().getCanonicalName();
		}

		@Override
		public InputStream getResourceAsStream(TemplateProcessingParameters theTemplateProcessingParameters, String theResourceName) {
			String template = myDatatypeClassNameToNarrativeTemplate.get(theResourceName);
			return new ReaderInputStream(new StringReader(template));
		}
	}

	public class MyProcessor extends AbstractAttrProcessor {

		protected MyProcessor() {
			super("narrative");
		}

		@Override
		public int getPrecedence() {
			return 0;
		}

		@Override
		protected ProcessorResult processAttribute(Arguments theArguments, Element theElement, String theAttributeName) {
			final String attributeValue = theElement.getAttributeValue(theAttributeName);

			final Configuration configuration = theArguments.getConfiguration();
			final IStandardExpressionParser expressionParser = StandardExpressions.getExpressionParser(configuration);

			final IStandardExpression expression = expressionParser.parseExpression(configuration, theArguments, attributeValue);
			final Object value = expression.execute(configuration, theArguments);

			Context context = new Context();
			QuantityDt datatype = new QuantityDt();
			context.setVariable("resource", value);

			String result = myDatatypeTemplateEngine.process(datatype.getClass().getCanonicalName(), context);
			Document dom = DOMUtils.getXhtmlDOMFor(new StringReader(result));

			theElement.removeAttribute(theAttributeName);
			theElement.clearChildren();

			Element firstChild = (Element) dom.getFirstChild();
			for (Node next : firstChild.getChildren()) {
				theElement.addChild(next);
			}

			return ProcessorResult.ok();
		}

	}

	private final class ProfileResourceResolver implements IResourceResolver {
		@Override
		public String getName() {
			return getClass().getCanonicalName();
		}

		@Override
		public InputStream getResourceAsStream(TemplateProcessingParameters theTemplateProcessingParameters, String theResourceName) {
			String template = myProfileToNarrativeTemplate.get(theResourceName);
			if (template == null) {
				ourLog.info("No narative template for resource profile: {}", theResourceName);
				return new ReaderInputStream(new StringReader(""));
			}
			return new ReaderInputStream(new StringReader(template));
		}
	}

}