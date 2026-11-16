/*
 * Copyright 2024 Automate The Planet Ltd.
 * Author: Miriam Kyoseva
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package solutions.bellatrix.playwright.components.shadowdom;

import com.microsoft.playwright.Playwright;
import lombok.experimental.UtilityClass;

@UtilityClass
public class ShadowDomService {
    private static final String SHADOW_ROOT_TAG = "shadow-root";

    public static String getShadowHtml(ShadowRoot shadowRoot) {
        var function = String.format("""
                el => {
                           function clone(element, tag) {
                               let cloneElement;
                             	if (element instanceof ShadowRoot && !tag) {
                                 cloneElement = new DocumentFragment();
                               } else if (tag) {
                                 cloneElement = document.createElement(tag);
                               }
                               else {
                                 cloneElement = element.cloneNode(false);
                                 if (element.firstChild && element.firstChild.nodeType === 3) {
                                   cloneElement.appendChild(element.firstChild.cloneNode(false));
                                 }
                               }

                               if (element.shadowRoot) {
                                   cloneElement.appendChild(clone(element.shadowRoot, "%s"));
                               }

                               if (element.children) {
                                   for (const child of element.children) {
                                       cloneElement.appendChild(clone(child, undefined));
                                   }
                               }

                               return cloneElement;
                           }
                       
                        	var temporaryDiv = document.createElement("div");
                         	temporaryDiv.appendChild(clone(el.shadowRoot, undefined));
                           return temporaryDiv.innerHTML;
                };
                """, SHADOW_ROOT_TAG);

        return (String)shadowRoot.evaluate(function);
    }

    public static void registerSelectors(Playwright playwright) {
        playwright.selectors().register("shadow", """
                (() => {
                    function css(shadowRoot, selector, elements) {
                        const matches = new Set(shadowRoot.querySelectorAll(selector));
                        for (const element of shadowRoot.querySelectorAll('*')) {
                            if (matches.has(element)) elements.push(element);
                            if (element.shadowRoot) css(element.shadowRoot, selector, elements);
                        }
                        return elements;
                    }

                    function xpath(root, expression) {
                        const shadow = root.shadowRoot;
                        const document = root.ownerDocument.implementation.createHTMLDocument('');
                        for (const child of shadow.childNodes) {
                            document.body.append(document.importNode(child, true));
                        }
                        const originals = shadow.querySelectorAll('*');
                        const copies = new Map(Array.from(document.body.querySelectorAll('*'),
                            (element, index) => [element, originals[index]]));
                        const matches = document.evaluate(expression, document.body, null,
                            XPathResult.ORDERED_NODE_SNAPSHOT_TYPE);
                        const elements = [];
                        for (let index = 0; index < matches.snapshotLength; index++) {
                            const original = copies.get(matches.snapshotItem(index));
                            if (original) elements.push(original);
                        }
                        return elements;
                    }

                    function queryAll(root, selector) {
                        if (!root.shadowRoot) return [];
                        if (selector === 'host') return [root];
                        if (selector === 'descendants') return css(root.shadowRoot, '*', []);
                        if (selector.startsWith('css:')) return css(root.shadowRoot, selector.slice(4), []);
                        return xpath(root, selector.slice('xpath:'.length));
                    }

                    return { query: (root, selector) => queryAll(root, selector)[0], queryAll };
                })()
                """);
    }
}
