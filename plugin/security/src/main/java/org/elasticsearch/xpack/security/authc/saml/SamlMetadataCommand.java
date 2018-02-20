/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.security.authc.saml;

import java.io.InputStream;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.stream.Collectors;

import joptsimple.OptionParser;
import joptsimple.OptionSet;
import joptsimple.OptionSpec;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.Logger;
import org.elasticsearch.cli.EnvironmentAwareCommand;
import org.elasticsearch.cli.ExitCodes;
import org.elasticsearch.cli.SuppressForbidden;
import org.elasticsearch.cli.Terminal;
import org.elasticsearch.cli.UserException;
import org.elasticsearch.common.Strings;
import org.elasticsearch.common.io.PathUtils;
import org.elasticsearch.common.logging.Loggers;
import org.elasticsearch.common.logging.ServerLoggers;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.util.LocaleUtils;
import org.elasticsearch.common.util.concurrent.ThreadContext;
import org.elasticsearch.env.Environment;
import org.elasticsearch.xpack.core.security.authc.RealmConfig;
import org.elasticsearch.xpack.core.security.authc.RealmSettings;
import org.elasticsearch.xpack.core.security.authc.saml.SamlRealmSettings;
import org.elasticsearch.xpack.security.authc.saml.SamlSpMetadataBuilder.ContactInfo;
import org.opensaml.saml.saml2.core.AuthnRequest;
import org.opensaml.saml.saml2.metadata.EntityDescriptor;
import org.opensaml.saml.saml2.metadata.impl.EntityDescriptorMarshaller;
import org.w3c.dom.Element;
import org.xml.sax.SAXException;

import static org.elasticsearch.xpack.core.security.authc.RealmSettings.getRealmType;
import static org.elasticsearch.xpack.security.authc.saml.SamlRealm.require;

/**
 * CLI tool to generate SAML Metadata for a Service Provider (realm)
 */
public class SamlMetadataCommand extends EnvironmentAwareCommand {

    static final String METADATA_SCHEMA = "saml-schema-metadata-2.0.xsd";

    private final OptionSpec<String> outputPathSpec;
    private final OptionSpec<Void> batchSpec;
    private final OptionSpec<String> realmSpec;
    private final OptionSpec<String> localeSpec;
    private final OptionSpec<String> serviceNameSpec;
    private final OptionSpec<String> attributeSpec;
    private final OptionSpec<String> orgNameSpec;
    private final OptionSpec<String> orgDisplayNameSpec;
    private final OptionSpec<String> orgUrlSpec;
    private final OptionSpec<Void> contactsSpec;

    public static void main(String[] args) throws Exception {
        new SamlMetadataCommand().main(args, Terminal.DEFAULT);
    }

    public SamlMetadataCommand() {
        super("Generate Service Provider Metadata for a SAML realm");
        outputPathSpec = parser.accepts("out", "path of the xml file that should be generated").withRequiredArg();
        batchSpec = parser.accepts("batch", "Do not prompt");
        realmSpec = parser.accepts("realm", "name of the elasticsearch realm for which metadata should be generated").withRequiredArg();
        localeSpec = parser.accepts("locale", "the locale to be used for elements that require a language").withRequiredArg();
        serviceNameSpec = parser.accepts("service-name", "the name to apply to the attribute consuming service").withRequiredArg();
        attributeSpec = parser.accepts("attribute", "additional SAML attributes to request").withRequiredArg();
        orgNameSpec = parser.accepts("organisation-name", "the name of the organisation operating this service").withRequiredArg();
        orgDisplayNameSpec = parser.accepts("organisation-display-name", "the display-name of the organisation operating this service")
                .availableIf(orgNameSpec).withRequiredArg();
        orgUrlSpec = parser.accepts("organisation-url", "the URL of the organisation operating this service")
                .requiredIf(orgNameSpec).withRequiredArg();
        contactsSpec = parser.accepts("contacts", "Include contact information in metadata").availableUnless(batchSpec);
    }

    @Override
    protected void execute(Terminal terminal, OptionSet options, Environment env) throws Exception {
        // OpenSAML prints a lot of _stuff_ at info level, that really isn't needed in a command line tool.
        ServerLoggers.setLevel(Loggers.getLogger("org.opensaml"), Level.WARN);

        final Logger logger = Loggers.getLogger(getClass());
        SamlUtils.initialize(logger);

        final EntityDescriptor descriptor = buildEntityDescriptor(terminal, options, env);
        final Path xml = writeOutput(terminal, options, descriptor);
        validateXml(terminal, xml);
    }

    // package-protected for testing
    EntityDescriptor buildEntityDescriptor(Terminal terminal, OptionSet options, Environment env) throws Exception {
        final boolean batch = options.has(batchSpec);

        final RealmConfig realm = findRealm(terminal, options, env);
        terminal.println(Terminal.Verbosity.VERBOSE,
                "Using realm configuration\n=====\n" + realm.settings().toDelimitedString('\n') + "=====");
        final Locale locale = findLocale(options);
        terminal.println(Terminal.Verbosity.VERBOSE, "Using locale: " + locale.toLanguageTag());

        final SpConfiguration spConfig = SamlRealm.getSpConfiguration(realm);
        final SamlSpMetadataBuilder builder = new SamlSpMetadataBuilder(locale, spConfig.getEntityId())
                .assertionConsumerServiceUrl(spConfig.getAscUrl())
                .singleLogoutServiceUrl(spConfig.getLogoutUrl())
                .encryptionCredential(spConfig.getEncryptionCredential())
                .signingCredential(spConfig.getSigningConfiguration().getCredential())
                .authnRequestsSigned(spConfig.getSigningConfiguration().shouldSign(AuthnRequest.DEFAULT_ELEMENT_LOCAL_NAME))
                .nameIdFormat(SamlRealmSettings.NAMEID_FORMAT.get(realm.settings()))
                .serviceName(option(serviceNameSpec, options, env.settings().get("cluster.name")));

        Map<String, String> attributes = getAttributeNames(options, realm);
        for (String attr : attributes.keySet()) {
            final String name;
            String friendlyName;
            final String settingName = attributes.get(attr);
            final String attributeSource = settingName == null ? "command line" : '"' + settingName + '"';
            if (attr.contains(":")) {
                name = attr;
                if (batch) {
                    friendlyName = settingName;
                } else {
                    friendlyName = terminal.readText("What is the friendly name for " +
                            attributeSource
                            + " attribute \"" + attr + "\" [default: " +
                            (settingName == null ? "none" : settingName) +
                            "] ");
                    if (Strings.isNullOrEmpty(friendlyName)) {
                        friendlyName = settingName;
                    }
                }
            } else {
                if (batch) {
                    throw new UserException(ExitCodes.CONFIG, "Option " + batchSpec.toString() + " is specified, but attribute "
                            + attr + " appears to be a FriendlyName value");
                }
                friendlyName = attr;
                name = requireText(terminal,
                        "What is the standard (urn) name for " + attributeSource + " attribute \"" + attr + "\" (required): ");
            }
            terminal.println(Terminal.Verbosity.VERBOSE, "Requesting attribute '" + name + "' (FriendlyName: '" + friendlyName + "')");
            builder.withAttribute(friendlyName, name);
        }

        if (options.has(orgNameSpec) && options.has(orgUrlSpec)) {
            String name = orgNameSpec.value(options);
            builder.organization(name, option(orgDisplayNameSpec, options, name), orgUrlSpec.value(options));
        }

        if (options.has(contactsSpec)) {
            terminal.println("\nPlease enter the personal details for each contact to be included in the metadata");
            do {
                final String givenName = requireText(terminal, "What is the given name for the contact: ");
                final String surName = requireText(terminal, "What is the surname for the contact: ");
                final String displayName = givenName + ' ' + surName;
                final String email = requireText(terminal, "What is the email address for " + displayName + ": ");
                String type;
                while (true) {
                    type = requireText(terminal, "What is the contact type for " + displayName + ": ");
                    if (ContactInfo.TYPES.containsKey(type)) {
                        break;
                    } else {
                        terminal.println("Type '" + type + "' is not valid. Valid values are "
                                + Strings.collectionToCommaDelimitedString(ContactInfo.TYPES.keySet()));
                    }
                }
                builder.withContact(type, givenName, surName, email);
            } while (terminal.promptYesNo("Enter details for another contact", true));
        }

        return builder.build();
    }

    private Path writeOutput(Terminal terminal, OptionSet options, EntityDescriptor descriptor) throws Exception {
        final EntityDescriptorMarshaller marshaller = new EntityDescriptorMarshaller();
        final Element element = marshaller.marshall(descriptor);
        final Path outputFile = resolvePath(option(outputPathSpec, options, "saml-elasticsearch-metadata.xml"));
        final Writer writer = Files.newBufferedWriter(outputFile);
        SamlUtils.print(element, writer, true);
        terminal.println("\nWrote SAML metadata to " + outputFile);
        return outputFile;
    }


    private void validateXml(Terminal terminal, Path xml) throws Exception {
        try (InputStream xmlInput = Files.newInputStream(xml)) {
            SamlUtils.validate(xmlInput, METADATA_SCHEMA);
            terminal.println(Terminal.Verbosity.VERBOSE, "The generated metadata file conforms to the SAML metadata schema");
        } catch (SAXException e) {
            terminal.println(Terminal.Verbosity.SILENT, "Error - The generated metadata file does not conform to the SAML metadata schema");
            terminal.println("While validating " + xml.toString() + " the follow errors were found:");
            printExceptions(terminal, e);
            throw new UserException(ExitCodes.CODE_ERROR, "Generated metadata is not valid");
        }
    }

    private void printExceptions(Terminal terminal, Throwable throwable) {
        terminal.println(" - " + throwable.getMessage());
        for (Throwable sup : throwable.getSuppressed()) {
            printExceptions(terminal, sup);
        }
        if (throwable.getCause() != null && throwable.getCause() != throwable) {
            printExceptions(terminal, throwable.getCause());
        }
    }

    @SuppressForbidden(reason = "CLI tool working from current directory")
    private Path resolvePath(String name) {
        return PathUtils.get(name).normalize();
    }

    private String requireText(Terminal terminal, String prompt) {
        String value = null;
        while (Strings.isNullOrEmpty(value)) {
            value = terminal.readText(prompt);
        }
        return value;
    }

    private <T> T option(OptionSpec<T> spec, OptionSet options, T defaultValue) {
        if (options.has(spec)) {
            return spec.value(options);
        } else {
            return defaultValue;
        }
    }

    /**
     * Map of saml-attribute name to configuration-setting name
     */
    private Map<String, String> getAttributeNames(OptionSet options, RealmConfig realm) {
        Map<String, String> attributes = new LinkedHashMap<>();
        for (String a : attributeSpec.values(options)) {
            attributes.put(a, null);
        }
        final Settings attributeSettings = realm.settings().getByPrefix(SamlRealmSettings.AttributeSetting.ATTRIBUTES_PREFIX);
        for (String key : sorted(attributeSettings.keySet())) {
            final String attr = attributeSettings.get(key);
            attributes.put(attr, key);
        }
        return attributes;
    }

    // We sort this Set so that it is deterministic for testing
    private SortedSet<String> sorted(Set<String> strings) {
        return new TreeSet<>(strings);
    }

    private RealmConfig findRealm(Terminal terminal, OptionSet options, Environment env) throws UserException {
        final Map<String, Settings> realms = RealmSettings.getRealmSettings(env.settings());
        if (options.has(realmSpec)) {
            final String name = realmSpec.value(options);
            final Settings settings = realms.get(name);
            if (settings == null) {
                throw new UserException(ExitCodes.CONFIG, "No such realm '" + name + "' defined in " + env.configFile());
            }
            final String realmType = getRealmType(settings);
            if (isSamlRealm(realmType)) {
                return buildRealm(name, settings, env);
            } else {
                throw new UserException(ExitCodes.CONFIG, "Realm '" + name + "' is not a SAML realm (is '" + realmType + "')");
            }
        } else {
            final List<Map.Entry<String, Settings>> saml = realms.entrySet().stream()
                    .filter(entry -> isSamlRealm(getRealmType(entry.getValue())))
                    .collect(Collectors.toList());
            if (saml.isEmpty()) {
                throw new UserException(ExitCodes.CONFIG, "There is no SAML realm configured in " + env.configFile());
            }
            if (saml.size() > 1) {
                terminal.println("Using configuration in " + env.configFile());
                terminal.println("Found multiple SAML realms: " + saml.stream().map(Map.Entry::getKey).collect(Collectors.joining(", ")));
                terminal.println("Use the -" + optionName(realmSpec) + " option to specify an explicit realm");
                throw new UserException(ExitCodes.CONFIG,
                        "Found multiple SAML realms, please specify one with '-" + optionName(realmSpec) + "'");
            }
            final Map.Entry<String, Settings> entry = saml.get(0);
            terminal.println("Building metadata for SAML realm " + entry.getKey());
            return buildRealm(entry.getKey(), entry.getValue(), env);
        }
    }

    private String optionName(OptionSpec<?> spec) {
        return spec.options().get(0);
    }

    private RealmConfig buildRealm(String name, Settings settings, Environment env) {
        return new RealmConfig(name, settings, env.settings(), env, new ThreadContext(env.settings()));
    }

    private boolean isSamlRealm(String realmType) {
        return SamlRealmSettings.TYPE.equals(realmType);
    }

    private Locale findLocale(OptionSet options) {
        if (options.has(localeSpec)) {
            return LocaleUtils.parse(localeSpec.value(options));
        } else {
            return Locale.getDefault();
        }
    }

    // For testing
    OptionParser getParser() {
        return parser;
    }
}
