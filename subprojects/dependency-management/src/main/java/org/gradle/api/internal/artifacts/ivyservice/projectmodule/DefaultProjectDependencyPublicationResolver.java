/*
 * Copyright 2018 the original author or authors.
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
package org.gradle.api.internal.artifacts.ivyservice.projectmodule;

import org.gradle.api.GradleException;
import org.gradle.api.InvalidUserDataException;
import org.gradle.api.NonNullApi;
import org.gradle.api.artifacts.ModuleVersionIdentifier;
import org.gradle.api.component.ComponentWithVariants;
import org.gradle.api.component.SoftwareComponent;
import org.gradle.api.component.SoftwareComponentVariant;
import org.gradle.api.internal.artifacts.DefaultModuleVersionIdentifier;
import org.gradle.api.internal.component.SoftwareComponentInternal;
import org.gradle.api.internal.project.ProjectInternal;
import org.gradle.api.internal.project.ProjectState;
import org.gradle.api.internal.project.ProjectStateRegistry;
import org.gradle.execution.ProjectConfigurer;
import org.gradle.internal.Cast;
import org.gradle.internal.deprecation.DeprecationLogger;
import org.gradle.internal.logging.text.TreeFormatter;
import org.gradle.internal.service.scopes.Scopes;
import org.gradle.internal.service.scopes.ServiceScope;
import org.gradle.util.Path;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * A service that will resolve a project identity path into publication coordinates. If the target variant
 * of the project is provided, this resolver will attempt to resolve the coordinates for that variant
 * specifically. Otherwise, it will attempt to resolve the of the project's root component as long as the
 * root component does not span across multiple coordinates.
 */
@NonNullApi
@ServiceScope(Scopes.Build.class)
public class DefaultProjectDependencyPublicationResolver implements ProjectDependencyPublicationResolver {
    private final ProjectPublicationRegistry publicationRegistry;
    private final ProjectConfigurer projectConfigurer;
    private final ProjectStateRegistry projects;

    private final VariantCoordinateResolverCache resolverCache = new VariantCoordinateResolverCache();

    public DefaultProjectDependencyPublicationResolver(ProjectPublicationRegistry publicationRegistry, ProjectConfigurer projectConfigurer, ProjectStateRegistry projects) {
        this.publicationRegistry = publicationRegistry;
        this.projectConfigurer = projectConfigurer;
        this.projects = projects;
    }

    @Override
    public <T> T resolveComponent(Class<T> coordsType, Path identityPath) {
        return withProjectLock(identityPath, project ->
            resolve(coordsType, identityPath, project, VariantCoordinateResolver::getComponentCoordinates)
        );
    }

    @Override
    public <T> T resolveVariant(Class<T> coordsType, Path identityPath) {
        return resolveVariant(coordsType, identityPath, null);
    }

    @Override
    public <T> T resolveVariant(Class<T> coordsType, Path identityPath, String resolvedVariant) {
        return withProjectLock(identityPath, project ->
            resolve(coordsType, identityPath, project, resolver ->
                resolver.getVariantCoordinates(resolvedVariant)
            )
        );
    }

    private <T> T withProjectLock(Path identityPath, Function<ProjectInternal, T> action) {
        ProjectState projectState = projects.stateFor(identityPath);
        // Could probably apply some caching and some immutable types

        // Ensure target project is configured
        projectConfigurer.configureFully(projectState);

        return projectState.fromMutableState(action);
    }

    private <T> T resolve(Class<T> coordsType, Path identityPath, ProjectInternal project, Function<VariantCoordinateResolver<T>, T> action) {
        VariantCoordinateResolver<T> resolver = resolverCache.get(coordsType, identityPath);
        if (resolver != null) {
            return action.apply(resolver);
        }

        List<ProjectComponentPublication> publications = new ArrayList<>();
        for (ProjectComponentPublication publication : publicationRegistry.getPublications(ProjectComponentPublication.class, identityPath)) {
            if (!publication.isLegacy() && publication.getCoordinates(coordsType) != null) {
                publications.add(publication);
            }
        }

        if (publications.isEmpty()) {

            // TODO: Deprecate this behavior
//            DeprecationLogger.deprecate("Publishing a project which depends on another project, while the target project has no publications")
//                .withAdvice("Ensure the target project declares at least one publication, do not publish the current project, or remove the dependency on the target project.")
//                .willBecomeAnErrorInGradle9()
//                .withUpgradeGuideSection(8, "publishing_dependency_on_unpublished_project")
//                .nagUser();

            // Project has no publications: simply use the project name in place of the dependency name
            if (coordsType.isAssignableFrom(ModuleVersionIdentifier.class)) {
                return coordsType.cast(DefaultModuleVersionIdentifier.newId(project.getGroup().toString(), project.getName(), project.getVersion().toString()));
            }
            throw new UnsupportedOperationException(String.format("Could not find any publications of type %s in %s.", coordsType.getSimpleName(), project.getDisplayName()));
        }

        // Select all entry points. An entry point is a publication that does not contain a component whose parent is also published
        Set<SoftwareComponent> children = getChildComponents(publications);
        Set<ProjectComponentPublication> topLevel = new LinkedHashSet<>();
        Set<ProjectComponentPublication> topLevelWithComponent = new LinkedHashSet<>();
        for (ProjectComponentPublication publication : publications) {
            SoftwareComponent component = publication.getComponent().getOrNull();
            if (!publication.isAlias() && !children.contains(component)) {
                topLevel.add(publication);
                if (component != null) {
                    topLevelWithComponent.add(publication);
                }
            }
        }

        if (topLevelWithComponent.size() == 1) {
            SoftwareComponentInternal singleComponent = topLevelWithComponent.iterator().next().getComponent().get();
            resolver = resolverCache.put(singleComponent, identityPath, coordsType, publications);
            return action.apply(resolver);
        }

        // The project either has no component or multiple components. In either case, fall-back
        // to a rudimentary behavior to return a single common coordinate for all publications, if possible.
        Iterator<ProjectComponentPublication> iterator = topLevel.iterator();
        T candidate = iterator.next().getCoordinates(coordsType);
        while (iterator.hasNext()) {
            T alternative = iterator.next().getCoordinates(coordsType);
            if (!candidate.equals(alternative)) {
                TreeFormatter formatter = new TreeFormatter();
                formatter.node("Publishing is not able to resolve a dependency on a project with multiple publications that have different coordinates.");
                formatter.node("Found the following publications in " + project.getDisplayName());
                formatter.startChildren();
                for (ProjectComponentPublication publication : topLevel) {
                    formatter.node(publication.getDisplayName().getCapitalizedDisplayName() + " with coordinates " + publication.getCoordinates(coordsType));
                }
                formatter.endChildren();
                throw new UnsupportedOperationException(formatter.toString());
            }
        }
        return candidate;
    }

    private static class VariantCoordinateResolverCache {
        private final Map<Key, VariantCoordinateResolver<?>> cache = new ConcurrentHashMap<>();

        @Nullable
        public <T> VariantCoordinateResolver<T> get(Class<T> coordsType, Path identityPath) {
            VariantCoordinateResolver<?> result = cache.get(new Key(identityPath, coordsType));
            return Cast.uncheckedCast(result);
        }

        public <T> VariantCoordinateResolver<T> put(SoftwareComponent root, Path identityPath, Class<T> coordsType, List<ProjectComponentPublication> publications) {
            Key key = new Key(identityPath, coordsType);
            assert !cache.containsKey(key);

            VariantCoordinateResolver<?> result = cache.computeIfAbsent(key, k -> createCoordinateResolver(root, identityPath, coordsType, publications));
            return Cast.uncheckedCast(result);
        }

        private static <T> VariantCoordinateResolver<T> createCoordinateResolver(SoftwareComponent root, Path identityPath, Class<T> coordsType, List<ProjectComponentPublication> publications) {
            Map<SoftwareComponent, T> coordinatesMap = new HashMap<>();
            for (ProjectComponentPublication publication : publications) {
                SoftwareComponent component = publication.getComponent().getOrNull();
                if (component != null && !publication.isAlias()) {
                    T coordinates = publication.getCoordinates(coordsType);
                    if (coordinates != null) {
                        coordinatesMap.put(component, coordinates);
                    }
                }
            }

            return new VariantCoordinateResolver<>(root, identityPath, coordinatesMap);
        }

        private static class Key {
            private final Path identityPath;
            private final Class<?> coordsType;

            public Key(Path identityPath, Class<?> coordsType) {
                this.identityPath = identityPath;
                this.coordsType = coordsType;
            }

            @Override
            public boolean equals(Object o) {
                if (this == o) {
                    return true;
                }
                if (o == null || getClass() != o.getClass()) {
                    return false;
                }
                Key key = (Key) o;
                return Objects.equals(identityPath, key.identityPath) && Objects.equals(coordsType, key.coordsType);
            }

            @Override
            public int hashCode() {
                return Objects.hash(identityPath, coordsType);
            }
        }
    }

    private static class VariantCoordinateResolver<T> {
        private final SoftwareComponent root;
        private final Path identityPath;
        private final Map<SoftwareComponent, T> componentsMap;
        private Map<String, T> variantCoordinatesMap;

        private VariantCoordinateResolver(SoftwareComponent root, Path identityPath, Map<SoftwareComponent, T> componentsMap) {
            this.root = root;
            this.identityPath = identityPath;
            this.componentsMap = componentsMap;
        }

        public T getComponentCoordinates() {
            return componentsMap.get(root);
        }

        public T getVariantCoordinates(@Nullable String resolvedVariant) {
            if (resolvedVariant != null) {
                T coordinates = getVariantCoordinates().get(resolvedVariant);
                if (coordinates == null) {
                    throw new InvalidUserDataException("Could not find variant '" + resolvedVariant + "' in component '" + root.getName() + "' of project '" + identityPath + "'");
                }
                return coordinates;
            } else {
                // We don't know which variant from the single component is actually requested.
                // If all variants in the component are published to the same coordinates,
                // return those coordinates. Otherwise, there are multiple potential coordinates
                // which may be correct.

                Set<T> allCoordinates = new HashSet<>();
                visitComponent(root, componentsMap, (name, coordinates) -> allCoordinates.add(coordinates));

                if (allCoordinates.isEmpty()) {
                    throw new GradleException("No coordinates configured for component " + root.getName());
                }
                if (allCoordinates.size() > 1) {
                    DeprecationLogger.deprecate("Publishing a component which depends on another multi-coordinate component, without using dependencyMapping")
                        .withAdvice("The published component metadata will be incomplete. Enable dependency mapping for this component to publish functional component metadata.")
                        .willBecomeAnErrorInGradle9()
                        .undocumented()
                        .nagUser();

                    // Return the root coordinates, to maintain existing behavior, even though these are likely wrong.
                    return componentsMap.get(root);
                }

                return allCoordinates.iterator().next();
            }
        }

        private Map<String, T> getVariantCoordinates() {
            if (variantCoordinatesMap != null) {
                return variantCoordinatesMap;
            }

            variantCoordinatesMap = new HashMap<>();
            visitComponent(root, componentsMap, (name, coordinates) -> {
                if (variantCoordinatesMap.containsKey(name)) {
                    throw new InvalidUserDataException("Found multiple variants with name '" + name + "' in component '" + root.getName() + "' of project '" + identityPath + "'");
                }
                variantCoordinatesMap.put(name, coordinates);
            });
            return variantCoordinatesMap;
        }

        interface ComponentVisitor<T> {
            void visitVariant(String name, T coordinates);
        }

        private static <T> void visitComponent(SoftwareComponent component, Map<SoftwareComponent, T> componentsMap, ComponentVisitor<T> visitor) {
            visitComponent(component, componentsMap, new HashSet<>(), new HashSet<>(), visitor);
        }

        private static <T> void visitComponent(
            SoftwareComponent component,
            Map<SoftwareComponent, T> componentsMap,
            Set<SoftwareComponent> componentsSeen,
            Set<T> coordinatesSeen,
            ComponentVisitor<T> visitor
        ) {
            if (!componentsSeen.add(component)) {
                throw new InvalidUserDataException("Circular dependency detected while resolving component coordinates.");
            }

            T coordinates = componentsMap.get(component);
            if (!coordinatesSeen.add(coordinates)) {
                throw new InvalidUserDataException("Multiple child components may not share the same coordinates.");
            }

            // First visit the local variants
            if (component instanceof SoftwareComponentInternal) {
                SoftwareComponentInternal componentInternal = (SoftwareComponentInternal) component;
                for (SoftwareComponentVariant variant : componentInternal.getUsages()) {
                    visitor.visitVariant(variant.getName(), coordinates);
                }
            }

            // Then visit all child components' variants
            if (component instanceof ComponentWithVariants) {
                ComponentWithVariants parent = (ComponentWithVariants) component;
                for (SoftwareComponent child : parent.getVariants()) {
                    visitComponent(child, componentsMap, componentsSeen, coordinatesSeen, visitor);
                }
            }
        }
    }

    private static Set<SoftwareComponent> getChildComponents(List<ProjectComponentPublication> publications) {
        Set<SoftwareComponent> children = new HashSet<>();
        for (ProjectComponentPublication publication : publications) {
            SoftwareComponent component = publication.getComponent().getOrNull();
            if (component instanceof ComponentWithVariants) {
                ComponentWithVariants parent = (ComponentWithVariants) component;
                // Child components are not top-level entry points.
                children.addAll(parent.getVariants());
            }
        }
        return children;
    }
}
