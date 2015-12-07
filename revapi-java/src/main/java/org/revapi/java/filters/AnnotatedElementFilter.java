/*
 * Copyright 2015 Red Hat, Inc. and/or its affiliates
 * and other contributors as indicated by the @author tags.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.revapi.java.filters;

import static java.util.stream.Collectors.toList;

import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.function.Predicate;
import java.util.regex.Pattern;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.lang.model.element.AnnotationMirror;

import org.jboss.dmr.ModelNode;
import org.revapi.AnalysisContext;
import org.revapi.Element;
import org.revapi.ElementFilter;
import org.revapi.java.spi.JavaMethodElement;
import org.revapi.java.spi.JavaModelElement;
import org.revapi.java.spi.JavaTypeElement;
import org.revapi.java.spi.Util;

/**
 * @author Lukas Krejci
 * @since 0.5.1
 */
public final class AnnotatedElementFilter implements ElementFilter {
    private static final String CONFIG_ROOT_PATH = "revapi.java.filter.annotated";
    private final IdentityHashMap<Object, Boolean> elementResults = new IdentityHashMap<>();
    private Predicate<String> includeTest;
    private Predicate<String> excludeTest;
    private boolean doNothing;

    private static Predicate<String> composeTest(List<String> fullMatches, List<Pattern> patterns) {
        if (fullMatches != null && fullMatches.size() > 0) {
            return s -> Collections.binarySearch(fullMatches, s) >= 0;
        } else if (patterns != null && patterns.size() > 0) {
            return s -> patterns.stream().anyMatch(p -> p.matcher(s).matches());
        } else {
            return null;
        }
    }

    @Override
    public void close() throws Exception {
        elementResults.clear();
    }

    @Override
    public @Nullable String[] getConfigurationRootPaths() {
        return new String[]{CONFIG_ROOT_PATH};
    }

    @Override
    public Reader getJSONSchema(@Nonnull String configurationRootPath) {
        if (CONFIG_ROOT_PATH.equals(configurationRootPath)) {
            return new InputStreamReader(getClass().getResourceAsStream("/META-INF/annotated-elem-filter-schema.json"),
                    Charset.forName("UTF-8"));
        } else {
            return null;
        }
    }

    @Override
    public void initialize(@Nonnull AnalysisContext analysisContext) {
        ModelNode root = analysisContext.getConfiguration().get("revapi", "java", "filter", "annotated");
        if (!root.isDefined()) {
            doNothing = true;
            return;
        }

        ModelNode regex = root.get("regex");
        boolean regexes = regex.isDefined() && regex.asBoolean();

        List<String> fullMatches = new ArrayList<>();
        List<Pattern> patterns = new ArrayList<>();

        readAnnotations(root.get("exclude"), regexes, fullMatches, patterns);

        this.excludeTest = composeTest(fullMatches, patterns);

        fullMatches = new ArrayList<>();
        patterns = new ArrayList<>();

        readAnnotations(root.get("include"), regexes, fullMatches, patterns);

        this.includeTest = composeTest(fullMatches, patterns);

        doNothing = includeTest == null && excludeTest == null;
    }

    @Override
    public boolean applies(@Nullable Element element) {
        return decide(element);
    }

    @Override
    public boolean shouldDescendInto(@Nullable Object element) {
        return doNothing || (element instanceof JavaTypeElement || element instanceof JavaMethodElement);
    }

    private boolean decide(@Nullable Object element) {
        //we don't exclude anything that we don't handle...
        if (doNothing || !(element instanceof JavaModelElement)) {
            return true;
        }

        @SuppressWarnings("SuspiciousMethodCalls")
        Boolean ret = elementResults.get(element);
        if (ret != null) {
            return ret;
        }

        JavaModelElement javaElement = (JavaModelElement) element;

        List<? extends AnnotationMirror> annos = javaElement.getModelElement().getAnnotationMirrors();

        if (annos.isEmpty()) {
            return includeTest == null;
        }

        List<String> includedAnnos = annos.stream().map(Util::toHumanReadableString)
                .filter(s -> includeTest == null || includeTest.test(s)).collect(toList());

        ret = !includedAnnos.isEmpty() && includedAnnos.stream().noneMatch(s -> excludeTest != null &&
                        excludeTest.test(s));

        elementResults.put(element, ret);
        return ret;
    }

    private void readAnnotations(ModelNode array, boolean regexes, List<String> fullMatches, List<Pattern> patterns) {
        if (!array.isDefined()) {
            return;
        }

        for (ModelNode ann : array.asList()) {
            String name = ann.asString();

            if (regexes) {
                patterns.add(Pattern.compile(name));
            } else {
                fullMatches.add(name);
            }
        }

        if (!regexes) {
            Collections.sort(fullMatches);
        }
    }
}