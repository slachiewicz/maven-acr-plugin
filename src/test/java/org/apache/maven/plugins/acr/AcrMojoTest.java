/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.maven.plugins.acr;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class AcrMojoTest {

    @Test
    void mainJarExcludesDoesNotModifyConfiguredExcludes() {
        List<String> excludes = new ArrayList<>(Arrays.asList("**/*DevOnly.class"));

        String[] first = AcrMojo.mainJarExcludes(excludes);
        String[] second = AcrMojo.mainJarExcludes(excludes);

        assertEquals(Arrays.asList("**/*DevOnly.class"), excludes);
        String[] expected = {"**/*DevOnly.class", "META-INF/application-client.xml"};
        assertArrayEquals(expected, first);
        assertArrayEquals(expected, second);
    }

    @Test
    void mainJarExcludesDefaultsToDescriptor() {
        assertArrayEquals(new String[] {"META-INF/application-client.xml"}, AcrMojo.mainJarExcludes(null));
    }
}
