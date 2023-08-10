/*
 * Copyright 2023 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.gradle.api.publish.internal.component;

import com.google.common.collect.Sets;
import org.apache.commons.lang.StringUtils;
import org.gradle.api.Action;
import org.gradle.api.InvalidUserCodeException;
import org.gradle.api.InvalidUserDataException;
import org.gradle.api.NamedDomainObjectContainer;
import org.gradle.api.artifacts.ConfigurablePublishArtifact;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.ConfigurationVariant;
import org.gradle.api.artifacts.PublishArtifactSet;
import org.gradle.api.attributes.AttributeContainer;
import org.gradle.api.component.ConfigurationVariantDetails;
import org.gradle.api.component.DependencyMappingDetails;
import org.gradle.api.internal.artifacts.configurations.ConfigurationInternal;
import org.gradle.api.internal.component.UsageContext;
import org.gradle.internal.Actions;
import org.gradle.internal.deprecation.DeprecationLogger;
import org.gradle.internal.reflect.Instantiator;
import org.jetbrains.annotations.Nullable;

import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

public class ConfigurationVariantMapping {
    private final ConfigurationInternal outgoingConfiguration;
    private Action<? super ConfigurationVariantDetails> action;
    private final Instantiator instantiator;

    public ConfigurationVariantMapping(ConfigurationInternal outgoingConfiguration, Action<? super ConfigurationVariantDetails> action, Instantiator instantiator) {
        this.outgoingConfiguration = outgoingConfiguration;
        this.action = action;
        this.instantiator = instantiator;
    }

    public void addAction(Action<? super ConfigurationVariantDetails> action) {
        this.action = Actions.composite(this.action, action);
    }

    public void visitVariants(Consumer<UsageContext> visitor) {
        outgoingConfiguration.preventFromFurtherMutation();

        if (!outgoingConfiguration.isTransitive()) {
            DeprecationLogger.warnOfChangedBehaviour("Publication ignores 'transitive = false' at configuration level", "Consider using 'transitive = false' at the dependency level if you need this to be published.")
                .withUserManual("publishing_ivy", "configurations_marked_as_non_transitive")
                .nagUser();
        }

        Set<String> seen = Sets.newHashSet();

        ConfigurationVariant defaultConfigurationVariant = instantiator.newInstance(DefaultConfigurationVariant.class, outgoingConfiguration);
        maybeVisitVariant(visitor, seen, defaultConfigurationVariant);

        NamedDomainObjectContainer<ConfigurationVariant> subvariants = outgoingConfiguration.getOutgoing().getVariants();
        for (ConfigurationVariant subvariant : subvariants) {
            maybeVisitVariant(visitor, seen, subvariant);
        }
    }

    private void maybeVisitVariant(
        Consumer<UsageContext> visitor,
        Set<String> seen,
        ConfigurationVariant subvariant
    ) {

        DefaultConfigurationVariantDetails details = instantiator.newInstance(DefaultConfigurationVariantDetails.class, subvariant);
        action.execute(details);

        if (!details.shouldPublish()) {
            return;
        }

        String outgoingConfigurationName = outgoingConfiguration.getName();
        String name = subvariant instanceof DefaultConfigurationVariant
            ? outgoingConfigurationName
            : outgoingConfigurationName + StringUtils.capitalize(subvariant.getName());

        if (!seen.add(name)) {
            throw new InvalidUserDataException("Cannot add feature variant '" + name + "' as a variant with the same name is already registered");
        }

        visitor.accept(new FeatureConfigurationVariant(
            name,
            outgoingConfiguration,
            subvariant,
            details.getMavenScope(),
            details.isOptional(),
            details.dependencyMappingDetails
        ));
    }

    // Cannot be private due to reflective instantiation
    static class DefaultConfigurationVariant implements ConfigurationVariant {
        private final ConfigurationInternal outgoingConfiguration;

        public DefaultConfigurationVariant(ConfigurationInternal outgoingConfiguration) {
            this.outgoingConfiguration = outgoingConfiguration;
        }

        @Override
        public PublishArtifactSet getArtifacts() {
            return outgoingConfiguration.getArtifacts();
        }

        @Override
        public void artifact(Object notation) {
            throw new InvalidUserCodeException("Cannot add artifacts during filtering");
        }

        @Override
        public void artifact(Object notation, Action<? super ConfigurablePublishArtifact> configureAction) {
            throw new InvalidUserCodeException("Cannot add artifacts during filtering");
        }

        @Override
        public String getName() {
            return outgoingConfiguration.getName();
        }

        @Override
        public Optional<String> getDescription() {
            return Optional.ofNullable(outgoingConfiguration.getDescription());
        }

        @Override
        public ConfigurationVariant attributes(Action<? super AttributeContainer> action) {
            throw new InvalidUserCodeException("Cannot mutate outgoing configuration during filtering");
        }

        @Override
        public AttributeContainer getAttributes() {
            return outgoingConfiguration.getAttributes();
        }
    }

    // Cannot be private due to reflective instantiation
    static class DefaultConfigurationVariantDetails implements ConfigurationVariantDetails {
        private final ConfigurationVariant variant;
        private boolean skip = false;
        private String mavenScope = "compile";
        private boolean optional = false;
        private DefaultDependencyMappingDetails dependencyMappingDetails;

        public DefaultConfigurationVariantDetails(ConfigurationVariant variant) {
            this.variant = variant;
        }

        @Override
        public ConfigurationVariant getConfigurationVariant() {
            return variant;
        }

        @Override
        public void skip() {
            skip = true;
        }

        @Override
        public void mapToOptional() {
            this.optional = true;
        }

        @Override
        public void mapToMavenScope(String scope) {
            this.mavenScope = assertValidScope(scope);
        }

        @Override
        public void dependencyMapping(Action<? super DependencyMappingDetails> action) {
            if (dependencyMappingDetails == null) {
                dependencyMappingDetails = new DefaultDependencyMappingDetails();
            }
            action.execute(dependencyMappingDetails);
        }

        private static String assertValidScope(String scope) {
            scope = scope.toLowerCase();
            if ("compile".equals(scope) || "runtime".equals(scope)) {
                return scope;
            }
            throw new InvalidUserCodeException("Invalid Maven scope '" + scope + "'. You must choose between 'compile' and 'runtime'");
        }

        public boolean shouldPublish() {
            return !skip;
        }

        public String getMavenScope() {
            return mavenScope;
        }

        public boolean isOptional() {
            return optional;
        }
    }

    private static class DefaultDependencyMappingDetails implements DependencyMappingDetailsInternal {

        private boolean publishResolvedCoordinates;
        private boolean publishResolvedVersions;
        private Configuration resolutionConfiguration;

        @Override
        public void publishResolvedCoordinates() {
            this.publishResolvedCoordinates = true;
        }

        @Override
        public void publishResolvedVersions() {
            this.publishResolvedVersions = true;
        }

        @Override
        public void fromResolutionOf(Configuration resolutionConfiguration) {
            this.resolutionConfiguration = resolutionConfiguration;
        }

        @Override
        public boolean shouldPublishResolvedCoordinates() {
            return publishResolvedCoordinates;
        }

        @Override
        public boolean shouldPublishResolvedVersions() {
            return publishResolvedVersions;
        }

        @Nullable
        @Override
        public Configuration getResolutionConfiguration() {
            return resolutionConfiguration;
        }
    }

}
