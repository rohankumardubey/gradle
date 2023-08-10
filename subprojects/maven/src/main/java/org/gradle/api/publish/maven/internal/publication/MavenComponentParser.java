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

package org.gradle.api.publish.maven.internal.publication;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Sets;
import org.gradle.api.GradleException;
import org.gradle.api.InvalidUserCodeException;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.DependencyArtifact;
import org.gradle.api.artifacts.DependencyConstraint;
import org.gradle.api.artifacts.ExcludeRule;
import org.gradle.api.artifacts.ModuleDependency;
import org.gradle.api.artifacts.ModuleIdentifier;
import org.gradle.api.artifacts.ModuleVersionIdentifier;
import org.gradle.api.artifacts.ProjectDependency;
import org.gradle.api.artifacts.component.ComponentIdentifier;
import org.gradle.api.artifacts.component.ComponentSelector;
import org.gradle.api.artifacts.component.ModuleComponentIdentifier;
import org.gradle.api.artifacts.component.ModuleComponentSelector;
import org.gradle.api.artifacts.component.ProjectComponentIdentifier;
import org.gradle.api.artifacts.component.ProjectComponentSelector;
import org.gradle.api.artifacts.result.DependencyResult;
import org.gradle.api.artifacts.result.ResolutionResult;
import org.gradle.api.artifacts.result.ResolvedDependencyResult;
import org.gradle.api.artifacts.result.ResolvedVariantResult;
import org.gradle.api.attributes.AttributeContainer;
import org.gradle.api.capabilities.Capability;
import org.gradle.api.component.SoftwareComponentVariant;
import org.gradle.api.internal.DocumentationRegistry;
import org.gradle.api.internal.artifacts.DefaultExcludeRule;
import org.gradle.api.internal.artifacts.DefaultModuleIdentifier;
import org.gradle.api.internal.artifacts.ImmutableModuleIdentifierFactory;
import org.gradle.api.internal.artifacts.ProjectComponentIdentifierInternal;
import org.gradle.api.internal.artifacts.dependencies.DefaultProjectDependencyConstraint;
import org.gradle.api.internal.artifacts.dependencies.ProjectDependencyInternal;
import org.gradle.api.internal.artifacts.dsl.dependencies.PlatformSupport;
import org.gradle.api.internal.artifacts.ivyservice.ivyresolve.strategy.DefaultVersionSelectorScheme;
import org.gradle.api.internal.artifacts.ivyservice.ivyresolve.strategy.MavenVersionSelectorScheme;
import org.gradle.api.internal.artifacts.ivyservice.projectmodule.ProjectDependencyPublicationResolver;
import org.gradle.api.internal.attributes.AttributeContainerInternal;
import org.gradle.api.internal.attributes.ImmutableAttributes;
import org.gradle.api.internal.component.SoftwareComponentInternal;
import org.gradle.api.logging.Logger;
import org.gradle.api.logging.Logging;
import org.gradle.api.publish.internal.component.MavenPublishingAwareVariant;
import org.gradle.api.publish.internal.component.ResolutionBackedVariant;
import org.gradle.api.publish.internal.validation.PublicationWarningsCollector;
import org.gradle.api.publish.internal.versionmapping.VariantVersionMappingStrategyInternal;
import org.gradle.api.publish.internal.versionmapping.VersionMappingStrategyInternal;
import org.gradle.api.publish.maven.MavenArtifact;
import org.gradle.api.publish.maven.internal.dependencies.DefaultMavenDependency;
import org.gradle.api.publish.maven.internal.dependencies.DefaultMavenPomDependencies;
import org.gradle.api.publish.maven.internal.dependencies.MavenDependency;
import org.gradle.api.publish.maven.internal.dependencies.MavenPomDependencies;
import org.gradle.api.publish.maven.internal.dependencies.VersionRangeMapper;
import org.gradle.api.publish.maven.internal.validation.MavenPublicationErrorChecker;
import org.gradle.internal.component.local.model.ProjectComponentSelectorInternal;
import org.gradle.internal.typeconversion.NotationParser;
import org.gradle.util.Path;

import javax.annotation.Nullable;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Encapsulates all logic required to extract data from a {@link SoftwareComponentInternal} in order to
 * transform it to a representation compatible with Maven.
 */
public class MavenComponentParser {
    @VisibleForTesting
    public static final String INCOMPATIBLE_FEATURE = " contains dependencies that will produce a pom file that cannot be consumed by a Maven client.";
    @VisibleForTesting
    public static final String UNSUPPORTED_FEATURE = " contains dependencies that cannot be represented in a published pom file.";
    @VisibleForTesting
    public static final String PUBLICATION_WARNING_FOOTER =
        "These issues indicate information that is lost in the published 'pom' metadata file, " +
        "which may be an issue if the published library is consumed by an old Gradle version or Apache Maven.\n" +
        "The 'module' metadata file, which is used by Gradle 6+ is not affected.";

    /*
     * Maven supports wildcards in exclusion rules according to:
     * http://www.smartjava.org/content/maven-and-wildcard-exclusions
     * https://issues.apache.org/jira/browse/MNG-3832
     * This should be used for non-transitive dependencies
     */
    private static final Set<ExcludeRule> EXCLUDE_ALL_RULE = Collections.singleton(new DefaultExcludeRule("*", "*"));

    private static final Logger LOG = Logging.getLogger(MavenComponentParser.class);


    private final PlatformSupport platformSupport;
    private final VersionRangeMapper versionRangeMapper;
    private final DocumentationRegistry documentationRegistry;
    private final ProjectDependencyPublicationResolver projectDependencyResolver;
    private final NotationParser<Object, MavenArtifact> mavenArtifactParser;
    private final ImmutableModuleIdentifierFactory moduleIdentifierFactory;

    public MavenComponentParser(
        PlatformSupport platformSupport,
        VersionRangeMapper versionRangeMapper,
        DocumentationRegistry documentationRegistry,
        ProjectDependencyPublicationResolver projectDependencyResolver,
        NotationParser<Object, MavenArtifact> mavenArtifactParser,
        ImmutableModuleIdentifierFactory moduleIdentifierFactory
    ) {
        this.platformSupport = platformSupport;
        this.versionRangeMapper = versionRangeMapper;
        this.documentationRegistry = documentationRegistry;
        this.projectDependencyResolver = projectDependencyResolver;
        this.mavenArtifactParser = mavenArtifactParser;
        this.moduleIdentifierFactory = moduleIdentifierFactory;
    }

    public Set<MavenArtifact> parseArtifacts(SoftwareComponentInternal component) {
        // TODO Artifact names should be determined by the source variant. We shouldn't
        //      blindly "pass-through" the artifact file name.
        Set<ArtifactKey> seenArtifacts = Sets.newHashSet();
        return getSortedVariants(component)
            .flatMap(variant -> variant.getArtifacts().stream())
            .filter(artifact -> {
                ArtifactKey key = new ArtifactKey(artifact.getFile(), artifact.getClassifier(), artifact.getExtension());
                return seenArtifacts.add(key);
            })
            .map(mavenArtifactParser::parseNotation)
            .collect(Collectors.toSet());
    }

    public ParsedDependencyResult parseDependencies(
        SoftwareComponentInternal component,
        ModuleVersionIdentifier coordinates,
        VersionMappingStrategyInternal versionMappingStrategy
    ) {
        MavenPublicationErrorChecker.checkForUnpublishableAttributes(component, documentationRegistry);

        final PublicationWarningsCollector publicationWarningsCollector = new PublicationWarningsCollector(
            LOG, UNSUPPORTED_FEATURE, INCOMPATIBLE_FEATURE, PUBLICATION_WARNING_FOOTER, "suppressPomMetadataWarningsFor");

        Set<PublishedDependency> seenDependencies = Sets.newHashSet();
        Set<DependencyConstraint> seenConstraints = Sets.newHashSet();

        List<MavenDependency> dependencies = new ArrayList<>();
        List<MavenDependency> constraints = new ArrayList<>();
        List<MavenDependency> platforms = new ArrayList<>();

        getSortedVariants(component).forEach(variant -> {
            publicationWarningsCollector.newContext(variant.getName());

            MavenPublishingAwareVariant.ScopeMapping scopeMapping = MavenPublishingAwareVariant.scopeForVariant(variant);
            String scope = scopeMapping.getScope();
            boolean optional = scopeMapping.isOptional();
            Set<ExcludeRule> globalExcludes = variant.getGlobalExcludes();

            VariantDependencyResolver dependencyResolver = createVariantDependencyResolver(variant, versionMappingStrategy);
            VisitingMavenDependencyFactory dependencyFactory = new VisitingMavenDependencyFactory(
                versionRangeMapper,
                publicationWarningsCollector,
                dependencyResolver,
                scope,
                optional,
                globalExcludes
            );

            for (ModuleDependency dependency : variant.getDependencies()) {
                if (seenDependencies.add(PublishedDependency.of(dependency))) {
                    if (isDependencyWithDefaultArtifact(dependency) && dependencyMatchesProject(dependency, coordinates)) {
                        // We skip all self referencing dependency declarations, unless they have custom artifact information
                        continue;
                    }
                    if (platformSupport.isTargetingPlatform(dependency)) {
                        dependencyFactory.visitPlatformDependency(dependency, platforms::add);
                    } else {
                        dependencyFactory.visitDependency(dependency, dependencies::add);
                    }
                }
            }

            for (DependencyConstraint dependency : variant.getDependencyConstraints()) {
                if (seenConstraints.add(dependency)) { // TODO: Why do we not use PublishedDependency here?
                    if (dependency instanceof DefaultProjectDependencyConstraint) {
                        dependencyFactory.visitProjectDependencyConstraint((DefaultProjectDependencyConstraint) dependency, constraints::add);
                    } else if (dependency.getVersion() != null) {
                        dependencyFactory.visitModuleDependencyConstraint(dependency, constraints::add);
                    } else {
                        // Some dependency constraints, like those with rejectAll() have no version and do not map to Maven.
                        publicationWarningsCollector.addIncompatible(String.format("constraint %s:%s declared with a Maven incompatible version notation", dependency.getGroup(), dependency.getName()));
                    }
                }
            }

            if (!variant.getCapabilities().isEmpty()) {
                for (Capability capability : variant.getCapabilities()) {
                    if (isNotDefaultCapability(capability, coordinates)) {
                        publicationWarningsCollector.addVariantUnsupported(String.format("Declares capability %s:%s:%s which cannot be mapped to Maven", capability.getGroup(), capability.getName(), capability.getVersion()));
                    }
                }
            }
        });

        return new ParsedDependencyResult(
            new DefaultMavenPomDependencies(
                ImmutableList.copyOf(dependencies),
                ImmutableList.<MavenDependency>builder().addAll(constraints).addAll(platforms).build()
            ),
            publicationWarningsCollector
        );
    }

    private static boolean isNotDefaultCapability(Capability capability, ModuleVersionIdentifier coordinates) {
        return !coordinates.getGroup().equals(capability.getGroup())
            || !coordinates.getName().equals(capability.getName())
            || !coordinates.getVersion().equals(capability.getVersion());
    }

    private static boolean isDependencyWithDefaultArtifact(ModuleDependency dependency) {
        if (dependency.getArtifacts().isEmpty()) {
            return true;
        }
        return dependency.getArtifacts().stream().allMatch(artifact -> Strings.nullToEmpty(artifact.getClassifier()).isEmpty());
    }

    private static boolean dependencyMatchesProject(ModuleDependency dependency, ModuleVersionIdentifier coordinates) {
        return coordinates.getModule().equals(DefaultModuleIdentifier.newId(dependency.getGroup(), dependency.getName()));
    }

    private static Stream<? extends SoftwareComponentVariant> getSortedVariants(SoftwareComponentInternal component) {
        return component.getUsages().stream()
            .sorted(Comparator.comparing(MavenPublishingAwareVariant::scopeForVariant));
    }

    private VariantDependencyResolver createVariantDependencyResolver(
        SoftwareComponentVariant variant,
        VersionMappingStrategyInternal versionMappingStrategy
    ) {
        Configuration configuration = null;
        boolean useResolvedVersion = false;
        boolean useResolvedCoordinates = false;
        boolean usingDependencyMapping = false;
        if (variant instanceof ResolutionBackedVariant) {
            ResolutionBackedVariant resolutionBackedVariant = (ResolutionBackedVariant) variant;
            useResolvedVersion = resolutionBackedVariant.getPublishResolvedVersions();
            useResolvedCoordinates = resolutionBackedVariant.getPublishResolvedCoordinates();
            usingDependencyMapping = useResolvedVersion || useResolvedCoordinates;

            configuration = resolutionBackedVariant.getResolutionConfiguration();
            if ((useResolvedCoordinates || useResolvedVersion) && configuration == null) {
                throw new InvalidUserCodeException("Cannot enable dependency mapping without configuring a resolution configuration.");
            }
        }

        ImmutableAttributes attributes = ((AttributeContainerInternal) variant.getAttributes()).asImmutable();
        VariantVersionMappingStrategyInternal versionMapping = versionMappingStrategy.findStrategyForVariant(attributes);

        // Fallback to version mapping if dependency mapping is not enabled
        if (!usingDependencyMapping && versionMapping.isEnabled()) {
            if (versionMapping.getResolutionConfiguration() != null) {
                configuration = versionMapping.getResolutionConfiguration();
                useResolvedVersion = true;
            }
        }

        VariantDependencyResolver dependencyResolver;
        if (configuration != null && (useResolvedCoordinates || useResolvedVersion)) {
            dependencyResolver = new DefaultVariantDependencyResolver(
                projectDependencyResolver,
                moduleIdentifierFactory,
                configuration,
                useResolvedVersion,
                useResolvedCoordinates
            );
        } else {
            dependencyResolver = new MinimalVariantDependencyResolver(projectDependencyResolver, moduleIdentifierFactory);
        }

        return dependencyResolver;
    }

    // TODO: This is the maven specific part.
    // We should minimize the logic in this class.
    private static class VisitingMavenDependencyFactory {

        private final VersionRangeMapper versionRangeMapper;
        private final PublicationWarningsCollector publicationWarningsCollector;
        private final VariantDependencyResolver dependencyResolver;

        private final String scope;
        private final boolean optional;
        private final Set<ExcludeRule> globalExcludes;

        public VisitingMavenDependencyFactory(
            VersionRangeMapper versionRangeMapper,
            PublicationWarningsCollector publicationWarningsCollector,
            VariantDependencyResolver dependencyResolver,
            String scope,
            boolean optional,
            Set<ExcludeRule> globalExcludes
        ) {
            this.versionRangeMapper = versionRangeMapper;
            this.publicationWarningsCollector = publicationWarningsCollector;
            this.dependencyResolver = dependencyResolver;
            this.scope = scope;
            this.optional = optional;
            this.globalExcludes = globalExcludes;
        }

        private void visitDependency(ModuleDependency dependency, Consumer<MavenDependency> visitor) {
            // TODO: With dependency mapping enabled, we should be able to resolve the true
            // coordinates for the with-attributes dependency, and thus should not need
            // to emit this warning.
            if (!dependency.getAttributes().isEmpty()) {
                publicationWarningsCollector.addUnsupported(String.format("%s:%s:%s declared with Gradle attributes", dependency.getGroup(), dependency.getName(), dependency.getVersion()));
            }
            // TODO: If the dependency has required capabilities, we should emit a similar warning.

            if (dependency instanceof ProjectDependency) {
                visitProjectDependency((ProjectDependency) dependency, visitor);
            } else {
                visitModuleDependency(dependency, visitor);
            }
        }

        private void visitModuleDependency(ModuleDependency dependency, Consumer<MavenDependency> visitor) {

            Set<ExcludeRule> allExcludeRules = getExcludeRules(globalExcludes, dependency);

            Coordinates coordinates = resolveModuleCoordinates(dependency);

            if (dependency.getArtifacts().isEmpty()) {
                visitor.accept(newDependency(coordinates, null, null, scope, allExcludeRules, optional));
            }

            for (DependencyArtifact artifact : dependency.getArtifacts()) {
                // TODO: Add tests for this.
                if (!artifact.getName().equals(dependency.getName())) {
                    publicationWarningsCollector.addIncompatible(String.format("artifact %s:%s:%s declared with name '%s' different than dependency", dependency.getGroup(), artifact.getName(), dependency.getVersion(), artifact.getName()));
                    continue;
                }

                visitor.accept(newDependency(coordinates, artifact.getType(), artifact.getClassifier(), scope, allExcludeRules, optional));
            }
        }

        private void visitProjectDependency(ProjectDependency dependency, Consumer<MavenDependency> visitor) {
            ModuleVersionIdentifier coordinates = dependencyResolver.resolveCoordinates(dependency);
            Set<ExcludeRule> allExcludeRules = getExcludeRules(globalExcludes, dependency);

            visitor.accept(newDependency(coordinates, null, null, scope, allExcludeRules, optional));
        }

        private void visitModuleDependencyConstraint(DependencyConstraint dependency, Consumer<MavenDependency> visitor) {

            // TODO: What is version is null? Is there a point to adding this constraint?

            // If the target component has multiple coordinates, include the declared
            // root coordinates and the present resolved child coordinates as constraints.
            List<ModuleIdentifier> externalCoordinates = dependencyResolver.getAllComponentCoordinates(dependency.getGroup(), dependency.getName());
            for (ModuleIdentifier identifier : externalCoordinates) {
                visitor.accept(new DefaultMavenDependency(
                    identifier.getGroup(), identifier.getName(), dependency.getVersion(),
                    null, null, null, Collections.emptySet(), false
                ));
            }
        }

        private void visitProjectDependencyConstraint(DefaultProjectDependencyConstraint dependency, Consumer<MavenDependency> visitor) {

            // TODO: Are project dependency constraints even mappable to Maven?
            // In gradle, they seemingly are only used to add attributes to concrete project dependencies.


//            // TODO: Will we actually find the target dependency in the resolution result? Do constraints appear in the result?
//            ModuleVersionIdentifier coordinates = dependencyResolver.resolveCoordinates(dependency.getProjectDependency());
//
//            // Do not publish scope, as it has too different of semantics in Maven
//            visitor.accept(newDependency(coordinates, null, null, null, Collections.emptySet(), false));

            throw new UnsupportedOperationException();
        }

        public void visitPlatformDependency(ModuleDependency dependency, Consumer<MavenDependency> visitor) {
            if (dependency instanceof ProjectDependency) {
                visitor.accept(asImportDependencyConstraint((ProjectDependency) dependency));
            } else {
                visitor.accept(asImportDependencyConstraint(dependency));
            }
        }

        private MavenDependency asImportDependencyConstraint(ModuleDependency dependency) {
            Coordinates coordinates = resolveModuleCoordinates(dependency);
            return newDependency(coordinates, "pom", null, "import", Collections.emptySet(), false);
        }

        private MavenDependency asImportDependencyConstraint(ProjectDependency dependency) {
            ModuleVersionIdentifier coordinates = dependencyResolver.resolveCoordinates(dependency);
            return newDependency(coordinates, "pom", null, "import", Collections.emptySet(), false);
        }

        private Coordinates resolveModuleCoordinates(ModuleDependency dependency) {
            String groupId = dependency.getGroup();
            String artifactId = dependency.getName();
            String version = dependency.getVersion();

            ModuleVersionIdentifier resolvedCoordinates = dependencyResolver.resolveCoordinates(dependency);
            if (resolvedCoordinates != null) {
                return Coordinates.from(resolvedCoordinates);
            }

            // Dependency mapping is disabled or did not discover coordinates. Attempt to convert Gradle's rich version notation to Maven's.
            if (version == null) {
                return new Coordinates(groupId, artifactId, null);
            }

            if (DefaultVersionSelectorScheme.isSubVersion(version) ||
                (DefaultVersionSelectorScheme.isLatestVersion(version) && !MavenVersionSelectorScheme.isSubstituableLatest(version))
            ) {
                publicationWarningsCollector.addIncompatible(String.format("%s:%s:%s declared with a Maven incompatible version notation", groupId, artifactId, version));
            }

            return new Coordinates(groupId, artifactId, versionRangeMapper.map(version));
        }

        private static MavenDependency newDependency(
            Coordinates coordinates,
            @Nullable String type,
            @Nullable String classifier,
            @Nullable String scope,
            Set<ExcludeRule> excludeRules,
            boolean optional
        ) {
            return new DefaultMavenDependency(
                coordinates.group, coordinates.name, coordinates.version,
                type, classifier, scope, excludeRules, optional
            );
        }

        private static MavenDependency newDependency(
            ModuleVersionIdentifier coordinates,
            @Nullable String type,
            @Nullable String classifier,
            @Nullable String scope,
            Set<ExcludeRule> excludeRules,
            boolean optional
        ) {
            return new DefaultMavenDependency(
                coordinates.getGroup(), coordinates.getName(), coordinates.getVersion(),
                type, classifier, scope, excludeRules, optional
            );
        }

        private static Set<ExcludeRule> getExcludeRules(Set<ExcludeRule> globalExcludes, ModuleDependency dependency) {
            if (!dependency.isTransitive()) {
                return EXCLUDE_ALL_RULE;
            }

            Set<ExcludeRule> excludeRules = dependency.getExcludeRules();
            if (excludeRules.isEmpty()) {
                return globalExcludes;
            }

            if (globalExcludes.isEmpty()) {
                return excludeRules;
            }

            return Sets.union(globalExcludes, excludeRules);
        }
    }

    public static class ParsedDependencyResult {
        private final MavenPomDependencies dependencies;
        private final PublicationWarningsCollector warnings;

        public ParsedDependencyResult(
            MavenPomDependencies dependencies,
            PublicationWarningsCollector warnings
        ) {
            this.warnings = warnings;
            this.dependencies = dependencies;
        }

        public MavenPomDependencies getDependencies() {
            return dependencies;
        }

        public PublicationWarningsCollector getWarnings() {
            return warnings;
        }
    }

    /**
     * Similar to {@link ModuleVersionIdentifier}, but allows a null version.
     */
    private static class Coordinates {
        public final String group;
        public final String name;
        public final String version;

        public Coordinates(String group, String name, @Nullable String version) {
            this.group = group;
            this.name = name;
            this.version = version;
        }

        public static Coordinates from(ModuleVersionIdentifier identifier) {
            return new Coordinates(identifier.getGroup(), identifier.getName(), identifier.getVersion());
        }
    }

    private static class ArtifactKey {
        final File file;
        final String classifier;
        final String extension;

        public ArtifactKey(File file, @Nullable String classifier, @Nullable String extension) {
            this.file = file;
            this.classifier = classifier;
            this.extension = extension;
        }

        @Override
        public boolean equals(Object obj) {
            ArtifactKey other = (ArtifactKey) obj;
            return file.equals(other.file) && Objects.equals(classifier, other.classifier) && Objects.equals(extension, other.extension);
        }

        @Override
        public int hashCode() {
            return file.hashCode() ^ Objects.hash(classifier, extension);
        }
    }

    /**
     * This is used to de-duplicate dependencies based on relevant contents.
     * In particular, versions are ignored.
     */
    private static class PublishedDependency {
        private final String group;
        private final String name;
        private final String targetConfiguration;
        private final AttributeContainer attributes;
        private final Set<DependencyArtifact> artifacts;
        private final Set<ExcludeRule> excludeRules;
        private final List<Capability> requestedCapabilities;

        private PublishedDependency(String group, String name, String targetConfiguration, AttributeContainer attributes, Set<DependencyArtifact> artifacts, Set<ExcludeRule> excludeRules, List<Capability> requestedCapabilities) {
            this.group = group;
            this.name = name;
            this.targetConfiguration = targetConfiguration;
            this.attributes = attributes;
            this.artifacts = artifacts;
            this.excludeRules = excludeRules;
            this.requestedCapabilities = requestedCapabilities;
        }

        static PublishedDependency of(ModuleDependency dep) {
            return new PublishedDependency(
                dep.getGroup(),
                dep.getName(),
                dep.getTargetConfiguration(),
                dep.getAttributes(),
                dep.getArtifacts(),
                dep.getExcludeRules(),
                dep.getRequestedCapabilities()
            );
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (o == null || getClass() != o.getClass()) {
                return false;
            }
            PublishedDependency that = (PublishedDependency) o;
            return Objects.equals(group, that.group) &&
                Objects.equals(name, that.name) &&
                Objects.equals(targetConfiguration, that.targetConfiguration) &&
                Objects.equals(attributes, that.attributes) &&
                Objects.equals(artifacts, that.artifacts) &&
                Objects.equals(excludeRules, that.excludeRules) &&
                Objects.equals(requestedCapabilities, that.requestedCapabilities);
        }

        @Override
        public int hashCode() {
            return Objects.hash(group, name, targetConfiguration, attributes, artifacts, excludeRules, requestedCapabilities);
        }
    }

    private interface VariantDependencyResolver {
        @Nullable
        ModuleVersionIdentifier resolveCoordinates(ModuleDependency dependency);

        ModuleVersionIdentifier resolveCoordinates(ProjectDependency dependency);

        List<ModuleIdentifier> getAllComponentCoordinates(String group, String module);

        List<ModuleVersionIdentifier> getAllComponentCoordinates(DefaultProjectDependencyConstraint dependency);
    }

    private static Path getIdentityPath(ProjectDependency dependency) {
        return ((ProjectDependencyInternal) dependency).getIdentityPath();
    }

    private static class MinimalVariantDependencyResolver implements VariantDependencyResolver {

        private final ProjectDependencyPublicationResolver projectDependencyResolver;
        private final ImmutableModuleIdentifierFactory moduleIdentifierFactory;

        public MinimalVariantDependencyResolver(
            ProjectDependencyPublicationResolver projectDependencyResolver,
            ImmutableModuleIdentifierFactory moduleIdentifierFactory
        ) {
            this.projectDependencyResolver = projectDependencyResolver;
            this.moduleIdentifierFactory = moduleIdentifierFactory;
        }

        @Override
        public ModuleVersionIdentifier resolveCoordinates(ProjectDependency dependency) {
            return projectDependencyResolver.resolveVariant(ModuleVersionIdentifier.class, getIdentityPath(dependency));
        }

        @Nullable
        @Override
        public ModuleVersionIdentifier resolveCoordinates(ModuleDependency dependency) {
            return null;
        }

        @Override
        public List<ModuleIdentifier> getAllComponentCoordinates(String group, String module) {
            return Collections.singletonList(moduleIdentifierFactory.module(group, module));
        }

        @Override
        public List<ModuleVersionIdentifier> getAllComponentCoordinates(DefaultProjectDependencyConstraint dependency) {
            return Collections.singletonList(projectDependencyResolver.resolveComponent(ModuleVersionIdentifier.class, getIdentityPath(dependency.getProjectDependency())));
        }
    }

    static class ResolvedMappings {
        final Map<ModuleDependencyKey, ModuleVersionIdentifier> resolvedModules;
        final Map<ProjectDependencyKey, ModuleVersionIdentifier> resolvedProjects;

        ResolvedMappings(
            Map<ModuleDependencyKey, ModuleVersionIdentifier> resolvedModules,
            Map<ProjectDependencyKey, ModuleVersionIdentifier> resolvedProjects
        ) {
            this.resolvedModules = resolvedModules;
            this.resolvedProjects = resolvedProjects;
        }
    }

    private static class DefaultVariantDependencyResolver implements VariantDependencyResolver {

        private final ProjectDependencyPublicationResolver projectDependencyResolver;
        private final ImmutableModuleIdentifierFactory moduleIdentifierFactory;
        private final Configuration resolutionConfiguration;
        private final boolean useResolvedVersion;
        private final boolean useResolvedCoordinates;

        private ResolvedMappings mappings;
        private Map<ModuleIdentifier, String> resolvedComponentVersions;

        public DefaultVariantDependencyResolver(
            ProjectDependencyPublicationResolver projectDependencyResolver,
            ImmutableModuleIdentifierFactory moduleIdentifierFactory,
            Configuration resolutionConfiguration,
            boolean useResolvedVersion,
            boolean useResolvedCoordinates
        ) {
            assert useResolvedCoordinates || useResolvedVersion; // use MinimalVariantDependencyResolver instead

            this.projectDependencyResolver = projectDependencyResolver;
            this.moduleIdentifierFactory = moduleIdentifierFactory;
            this.resolutionConfiguration = resolutionConfiguration;
            this.useResolvedVersion = useResolvedVersion;
            this.useResolvedCoordinates = useResolvedCoordinates;
        }

        private ResolvedMappings calculateMappings() {
            Map<ModuleDependencyKey, ModuleVersionIdentifier> resolvedModules = new HashMap<>();
            Map<ProjectDependencyKey, ModuleVersionIdentifier> resolvedProjects = new HashMap<>();

            ResolutionResult resolutionResult = resolutionConfiguration.getIncoming().getResolutionResult();
            for (DependencyResult dependencyResult : resolutionResult.getRoot().getDependencies()) {
                if (!(dependencyResult instanceof ResolvedDependencyResult)) {
                    continue;
                }

                ComponentSelector requested = dependencyResult.getRequested();
                ResolvedVariantResult selected = ((ResolvedDependencyResult) dependencyResult).getResolvedVariant();

                // TODO: What happens if there are duplicate declared dependencies?
                // Constraints?
                // TODO: What happens if someone requests a target component?
                // What about if it endorses strict versions?
                // Transitive? Excludes? Artifacts?

                if (requested instanceof ModuleComponentSelector) {
                    ModuleComponentSelector requestedModule = (ModuleComponentSelector) requested;
                    ModuleDependencyKey key = new ModuleDependencyKey(requestedModule.getModuleIdentifier(), ModuleDependencyDetails.from(requested));
                    if (resolvedModules.put(key, getResolvedModuleComponent(selected)) != null) {
                        // TODO: Replace with warning. When does this happen?
                        throw new IllegalStateException("Multiple dependencies which map to same key");
                    }
                } else if (requested instanceof ProjectComponentSelector) {
                    ProjectComponentSelectorInternal requestedProject = (ProjectComponentSelectorInternal) requested;
                    ProjectDependencyKey key = new ProjectDependencyKey(requestedProject.getIdentityPath(), ModuleDependencyDetails.from(requested));
                    if (resolvedProjects.put(key, getResolvedModuleComponent(selected)) != null) {
                        // TODO: Replace with warning. When does this happen?
                        throw new IllegalStateException("Multiple dependencies which map to same key");
                    }
                }
            }

            return new ResolvedMappings(resolvedModules, resolvedProjects);
        }

        private Map<ModuleIdentifier, String> calculateResolvedComponentVersions() {
            ResolutionResult resolutionResult = resolutionConfiguration.getIncoming().getResolutionResult();
            Map<ModuleIdentifier, String> resolvedComponentVersions = new HashMap<>();
            resolutionResult.allComponents(component -> {
                ModuleVersionIdentifier moduleVersion = component.getModuleVersion();
                resolvedComponentVersions.put(moduleVersion.getModule(), moduleVersion.getVersion());
            });
            return resolvedComponentVersions;
        }

        private ResolvedMappings getMappings() {
            if (mappings == null) {
                mappings = calculateMappings();
            }
            return mappings;
        }

        private Map<ModuleIdentifier, String> getResolvedComponentVersions() {
            if (resolvedComponentVersions == null) {
                resolvedComponentVersions = calculateResolvedComponentVersions();
            }
            return resolvedComponentVersions;
        }

        private ModuleVersionIdentifier getResolvedModuleComponent(ResolvedVariantResult variant) {
            ComponentIdentifier componentId = variant.getOwner();
            if (componentId instanceof ProjectComponentIdentifier) {
                Path identityPath = ((ProjectComponentIdentifierInternal) componentId).getIdentityPath();

                // TODO: Using the display name here is ugly, but it the same as the configuration name.
                String variantName = variant.getDisplayName();

                // TODO: Eventually, this data should be exposed by getExternalVariant in the resolution result.
                return projectDependencyResolver.resolveVariant(ModuleVersionIdentifier.class, identityPath, variantName);
            } else if (componentId instanceof ModuleComponentIdentifier) {
                ResolvedVariantResult externalVariant = variant.getExternalVariant().orElse(null);

                if (externalVariant != null) {
                    ComponentIdentifier owningComponent = externalVariant.getOwner();
                    if (owningComponent instanceof ModuleComponentIdentifier) {
                        ModuleComponentIdentifier moduleComponentId = (ModuleComponentIdentifier) owningComponent;
                        return moduleIdentifierFactory.moduleWithVersion(moduleComponentId.getModuleIdentifier(), moduleComponentId.getVersion());
                    }
                    throw new GradleException("Expected owning component of module component to be a module component: " + owningComponent);
                }

                ModuleComponentIdentifier moduleId = (ModuleComponentIdentifier) componentId;

                return moduleIdentifierFactory.moduleWithVersion(moduleId.getModuleIdentifier(), moduleId.getVersion());
            } else {
                throw new UnsupportedOperationException("Unexpected component identifier type: " + componentId);
            }
        }

        @Override
        public ModuleVersionIdentifier resolveCoordinates(ProjectDependency dependency) {
            // TODO: Use useResolvedVersion and useResolvedCoordinates
            Path identityPath = getIdentityPath(dependency);

            ModuleDependencyDetails dependencyDetails = ModuleDependencyDetails.from(dependency);
            if (dependencyDetails != null) {
                ProjectDependencyKey key = new ProjectDependencyKey(identityPath, dependencyDetails);
                ModuleVersionIdentifier resolved = getMappings().resolvedProjects.get(key);
                if (resolved != null) {
                    return resolved;
                }

                // This is likely a user error. the resolutionConfiguration should have the
                // same dependencies as the variant being published.
            }

            // For some reason, we can't find the dependency in the resolution result.
            // Fall-back to version mapping only.
            ModuleVersionIdentifier identifier = projectDependencyResolver.resolveVariant(ModuleVersionIdentifier.class, identityPath);

            String resolvedVersion = getResolvedComponentVersions().get(identifier.getModule());
            return resolvedVersion != null ? moduleIdentifierFactory.moduleWithVersion(identifier.getModule(), resolvedVersion) : identifier;
        }

        @Nullable
        @Override
        public ModuleVersionIdentifier resolveCoordinates(ModuleDependency dependency) {
            // TODO: Use useResolvedVersion and useResolvedCoordinates
            ModuleDependencyDetails dependencyDetails = ModuleDependencyDetails.from(dependency);
            ModuleIdentifier module = DefaultModuleIdentifier.newId(dependency.getGroup(), dependency.getName());

            if (dependencyDetails != null) {
                ModuleDependencyKey key = new ModuleDependencyKey(module, dependencyDetails);
                ModuleVersionIdentifier resolved = getMappings().resolvedModules.get(key);
                if (resolved != null) {
                    return resolved;
                }

                // This is likely a user error. the resolutionConfiguration should have the
                // same dependencies as the variant being published.
            }

            // Fall-back to version mapping only.

            String resolvedVersion = getResolvedComponentVersions().get(module);
            return resolvedVersion != null
                ? moduleIdentifierFactory.moduleWithVersion(dependency.getGroup(), dependency.getName(), resolvedVersion)
                : null;
        }

        @Override
        public List<ModuleIdentifier> getAllComponentCoordinates(String group, String module) {
            Stream<ModuleIdentifier> resolvedCoordinates = getMappings().resolvedModules.entrySet().stream().filter(entry -> {
                ModuleDependencyKey key = entry.getKey();
                return key.module.getGroup().equals(group) && key.module.getName().equals(module);
            })
            .map(entry -> entry.getValue().getModule());

            // TODO: Make sure this is right.

            // The correct thing to do would be to resolve _all_ variants for the requested coordinates,
            // and add a constraint for each of them. However, the current API does not allow us to do this for
            // a single set of coordinates. Instead, we only include the external coordinates for variants that
            // were actually resolved.
            return Stream.concat(
                // Sometimes, including the requested coordinates is wrong, for example if the requested component
                // was substituted for a different component, or if the component is multi-coordinate and the root
                // coordinates are not relevant to this resolved graph.
                Stream.of(DefaultModuleIdentifier.newId(group, module)),
                resolvedCoordinates
            ).collect(Collectors.toList());
        }

        @Override
        public List<ModuleVersionIdentifier> getAllComponentCoordinates(DefaultProjectDependencyConstraint dependency) {
            throw new UnsupportedOperationException();
        }
    }

    static class ModuleDependencyKey {
        private final ModuleIdentifier module;
        private final ModuleDependencyDetails details;

        public ModuleDependencyKey(ModuleIdentifier module, ModuleDependencyDetails details) {
            this.module = module;
            this.details = details;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (o == null || getClass() != o.getClass()) {
                return false;
            }
            ModuleDependencyKey that = (ModuleDependencyKey) o;
            return Objects.equals(module, that.module) && Objects.equals(details, that.details);
        }

        @Override
        public int hashCode() {
            return Objects.hash(module, details);
        }
    }

    static class ProjectDependencyKey {
        private final Path idenityPath;
        private final ModuleDependencyDetails details;

        public ProjectDependencyKey(Path idenityPath, ModuleDependencyDetails details) {
            this.idenityPath = idenityPath;
            this.details = details;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (o == null || getClass() != o.getClass()) {
                return false;
            }
            ProjectDependencyKey that = (ProjectDependencyKey) o;
            return Objects.equals(idenityPath, that.idenityPath) && Objects.equals(details, that.details);
        }

        @Override
        public int hashCode() {
            return Objects.hash(idenityPath, details);
        }
    }

    private static class ModuleDependencyDetails {
        final AttributeContainer requestAttributes;
        final List<Capability> requestCapabilities;

        public ModuleDependencyDetails(
            AttributeContainer requestAttributes,
            List<Capability> requestCapabilities
        ) {
            this.requestAttributes = requestAttributes;
            this.requestCapabilities = requestCapabilities;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (o == null || getClass() != o.getClass()) {
                return false;
            }
            ModuleDependencyDetails that = (ModuleDependencyDetails) o;
            return Objects.equals(requestAttributes, that.requestAttributes) && Objects.equals(requestCapabilities, that.requestCapabilities);
        }

        @Override
        public int hashCode() {
            return Objects.hash(requestAttributes, requestCapabilities);
        }

        @Nullable
        public static ModuleDependencyDetails from(ModuleDependency dependency) {
            // The target configuration is not present in the component selector, so
            // we can't extract these dependencies from a resolution result's component selector.
            if (dependency.getTargetConfiguration() != null) {
                return null;
            }

            return new ModuleDependencyDetails(
                dependency.getAttributes(),
                dependency.getRequestedCapabilities()
            );
        }

        public static ModuleDependencyDetails from(ComponentSelector componentSelector) {
            return new ModuleDependencyDetails(
                componentSelector.getAttributes(),
                componentSelector.getRequestedCapabilities()
            );
        }
    }
}
