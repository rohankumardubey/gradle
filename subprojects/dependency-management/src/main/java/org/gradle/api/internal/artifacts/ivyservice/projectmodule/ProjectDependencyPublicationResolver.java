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

import org.gradle.api.artifacts.ProjectDependency;
import org.gradle.api.internal.artifacts.dependencies.ProjectDependencyInternal;
import org.gradle.util.Path;

/**
 * Given project coordinates, and optionally the name of a variant in that project,
 * determine the coordinates which should be used in other published metadata to reference
 * the project or its variant.
 *
 * TODO: Eventually, this data should be made available to dependency-management and
 * exposed through a ResolutionResult.
 */
public interface ProjectDependencyPublicationResolver {

    <T> T resolveComponent(Class<T> coordsType, Path identityPath);

    /**
     * Determines the coordinates of the given type that should be used to reference the
     * project identified by {@code identityPath}.
     *
     * <p>This behaves similarly to {@link #resolveComponent}, but emits a deprecation warning
     * if providing a {@code resolveVariant} would have produced a different result.</p>
     */
    <T> T resolveVariant(Class<T> coordsType, Path identityPath);

    /**
     * Determines the coordinates of the given type that should be used to reference the
     * variant with name {@code resolvedVariant}, contained in the project identified
     * by {@code identityPath}.
     */
    <T> T resolveVariant(Class<T> coordsType, Path identityPath, String resolvedVariant);
}
