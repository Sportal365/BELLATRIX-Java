/*
 * Copyright 2022 Automate The Planet Ltd.
 * Author: Anton Angelov
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package solutions.bellatrix.web.pages;

import lombok.AllArgsConstructor;
import lombok.Getter;
import solutions.bellatrix.web.components.Div;
import solutions.bellatrix.web.components.shadowdom.ShadowRoot;
import solutions.bellatrix.web.services.App;
import solutions.bellatrix.web.services.ComponentCreateService;

public abstract class PageMap {

    @Getter
    @AllArgsConstructor
    public enum ShadowHostType {
        SIDEBAR("sidebar"),
        TOP_BAR("top-bar"),
        CMS_TNG("cms-tng");

        private final String value;
    }

    public ComponentCreateService create() {
        return app().create();
    }

    public App app() {
        return new App();
    }

    public ShadowRoot cmsShadowRoot(ShadowHostType hostType) {
        return create().byXPath(Div.class, String.format("//div[@data-shadow-host='%s']", hostType.getValue()))
                .getShadowRoot();
    }
}