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

package solutions.bellatrix.playwright.components;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.PlaywrightException;
import com.microsoft.playwright.TimeoutError;
import com.microsoft.playwright.assertions.LocatorAssertions;
import com.microsoft.playwright.assertions.PlaywrightAssertions;
import com.microsoft.playwright.options.AriaRole;
import com.microsoft.playwright.options.BoundingBox;
import com.microsoft.playwright.options.SelectOption;
import com.microsoft.playwright.options.WaitForSelectorState;
import layout.LayoutComponentValidationsBuilder;
import lombok.Getter;
import lombok.Setter;
import lombok.SneakyThrows;
import org.apache.commons.text.StringEscapeUtils;
import solutions.bellatrix.core.plugins.EventListener;
import solutions.bellatrix.core.utilities.InstanceFactory;
import solutions.bellatrix.playwright.components.common.ComponentActionEventArgs;
import solutions.bellatrix.playwright.components.common.create.RelativeCreateService;
import solutions.bellatrix.playwright.components.common.webelement.FrameElement;
import solutions.bellatrix.playwright.components.common.webelement.WebElement;
import solutions.bellatrix.playwright.components.contracts.Component;
import solutions.bellatrix.playwright.components.contracts.ComponentStyle;
import solutions.bellatrix.playwright.components.contracts.ComponentVisible;
import solutions.bellatrix.playwright.components.enums.CssStyle;
import solutions.bellatrix.playwright.components.options.actions.CheckOptions;
import solutions.bellatrix.playwright.components.options.actions.ClickOptions;
import solutions.bellatrix.playwright.components.options.actions.HoverOptions;
import solutions.bellatrix.playwright.components.options.actions.UncheckOptions;
import solutions.bellatrix.playwright.components.options.states.BoundingBoxOptions;
import solutions.bellatrix.playwright.components.shadowdom.ShadowRoot;
import solutions.bellatrix.playwright.configuration.WebSettings;
import solutions.bellatrix.playwright.findstrategies.*;
import solutions.bellatrix.playwright.findstrategies.options.*;
import solutions.bellatrix.playwright.infrastructure.BrowserTypes;
import solutions.bellatrix.playwright.infrastructure.PlaywrightService;
import solutions.bellatrix.playwright.infrastructure.WrappedBrowser;
import solutions.bellatrix.playwright.services.BrowserService;
import solutions.bellatrix.playwright.utilities.Settings;

import java.awt.*;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.regex.Pattern;

import static org.apache.commons.lang3.StringEscapeUtils.unescapeHtml4;


@SuppressWarnings({"unchecked", "SameParameterValue", "unused"})
public class WebComponent extends LayoutComponentValidationsBuilder implements Component, ComponentVisible, ComponentStyle {
    public final static EventListener<ComponentActionEventArgs> HOVERING = new EventListener<>();
    public final static EventListener<ComponentActionEventArgs> HOVERED = new EventListener<>();
    public final static EventListener<ComponentActionEventArgs> FOCUSING = new EventListener<>();
    public final static EventListener<ComponentActionEventArgs> FOCUSED = new EventListener<>();
    public final static EventListener<ComponentActionEventArgs> SCROLLING_TO_VISIBLE = new EventListener<>();
    public final static EventListener<ComponentActionEventArgs> SCROLLED_TO_VISIBLE = new EventListener<>();
    public final static EventListener<ComponentActionEventArgs> SETTING_ATTRIBUTE = new EventListener<>();
    public final static EventListener<ComponentActionEventArgs> ATTRIBUTE_SET = new EventListener<>();
    public final static EventListener<ComponentActionEventArgs> RETURNING_WRAPPED_ELEMENT = new EventListener<>();
    public final static EventListener<ComponentActionEventArgs> CREATING = new EventListener<>();
    public final static EventListener<ComponentActionEventArgs> CREATED = new EventListener<>();

    protected WebSettings webSettings;
    @Getter protected final BrowserService browserService = new BrowserService();
    @Getter @Setter private WebComponent parentComponent;
    @Getter @Setter private int elementIndex;
    @Getter @Setter protected FindStrategy findStrategy;
    protected WebElement wrappedElement;
    protected RelativeCreateService createService;

    public WebComponent() {
        createService = new RelativeCreateService(this, CREATING, CREATED);
        webSettings = Settings.web();
    }

    public WebComponent(WebElement element) {
        this.wrappedElement = element;

        createService = new RelativeCreateService(this, CREATING, CREATED);
        webSettings = Settings.web();
    }

    /**
     * Convert this component to another type of component.
     * @param componentClass type of component
     */
    public <ComponentT extends WebComponent> ComponentT as(Class<ComponentT> componentClass) {
        if (this.getClass() == componentClass) return (ComponentT)this;

        var component = InstanceFactory.create(componentClass);

        if (Frame.class.isAssignableFrom(componentClass) && !(wrappedElement instanceof FrameElement)) {
            component.setWrappedElement(this.wrappedElement.locateFrame());
        } else if (!Frame.class.isAssignableFrom(componentClass) && wrappedElement instanceof FrameElement frame) {
            component.setWrappedElement(frame.owner());
        } else {
            component.setWrappedElement(this.wrappedElement);
        }

        component.setFindStrategy(findStrategy);
        component.setParentComponent(this.parentComponent);
        component.setElementIndex(this.elementIndex);

        return component;
    }

    public WebElement getWrappedElement() {
        RETURNING_WRAPPED_ELEMENT.broadcast(new ComponentActionEventArgs(this));
        return wrappedElement;
    }

    public void setWrappedElement(WebElement element) {
        wrappedElement = element;
    }

    public RelativeCreateService create() {
        return createService;
    }

    protected static WrappedBrowser wrappedBrowser() {
        return PlaywrightService.wrappedBrowser();
    }

    public void scrollToVisible() {
        SCROLLING_TO_VISIBLE.broadcast(new ComponentActionEventArgs(this));
        getWrappedElement().scrollIntoViewIfNeeded();
        SCROLLED_TO_VISIBLE.broadcast(new ComponentActionEventArgs(this));
    }

    public void scrollToTop() {
        wrappedElement.evaluate("element => element.scrollIntoView({block: 'start'})");
    }

    public void scrollToBottom() {
        wrappedElement.evaluate("element => element.scrollIntoView({block: 'end'})");
    }

    public void focus() {
        FOCUSING.broadcast(new ComponentActionEventArgs(this));
        getWrappedElement().focus();
        FOCUSED.broadcast(new ComponentActionEventArgs(this));
    }

    public void hover() {
        HOVERING.broadcast(new ComponentActionEventArgs(this));
        internalHover(null);
        HOVERED.broadcast(new ComponentActionEventArgs(this));
    }

    public void hover(Function<Locator.HoverOptions, Locator.HoverOptions> options) {
        HOVERING.broadcast(new ComponentActionEventArgs(this));
        internalHover(HoverOptions.create(options));
        HOVERED.broadcast(new ComponentActionEventArgs(this));
    }

    private void internalHover(HoverOptions options) {
        if (options == null) {
            getWrappedElement().hover();
        } else {
            getWrappedElement().hover(options.convert());
        }
    }

    public Class<?> getComponentClass() {
        return this.getClass();
    }

    public String getStyle() {
        return getAttribute("style");
    }

    public String getCssValue(String propertyName) {
        return wrappedElement.getCssValue(propertyName);
    }

    public String getCssValue(CssStyle property) {
        return getCssValue(property.toString());
    }

    public String getComponentName() {
        var description = findStrategy == null ? wrappedElement : findStrategy;
        return String.format("%s (%s)", getComponentClass().getSimpleName(), description);
    }

    public String getTagName() {
        return String.valueOf(wrappedElement.evaluate("element => element.localName"));
    }

    public WebElement findLocator() {
        return wrappedElement;
    }

    public ShadowRoot getShadowRoot() {
        return this instanceof ShadowRoot shadowRoot ? shadowRoot : as(ShadowRoot.class);
    }

    @Override
    public Point getLocation() {
        return new Point((int)getBoundingBox().x, (int)getBoundingBox().y);
    }

    @Override
    public Dimension getSize() {
        return new Dimension((int)getBoundingBox().width, (int)getBoundingBox().height);
    }

    public BoundingBox getBoundingBox() {
        return wrappedElement.boundingBox();
    }

    public BoundingBox getBoundingBox(Function<Locator.BoundingBoxOptions, Locator.BoundingBoxOptions> options) {
        return wrappedElement.boundingBox(BoundingBoxOptions.create(options).convert());
    }

    public String getAttribute(String name) {
        return findLocator().getAttribute(name);
    }

    public String getHtmlClass() {
        return getAttribute("class");
    }

    public String getTitle() {
        return getAttribute("title");
    }

    public void setAttribute(String name, String value) {
        SETTING_ATTRIBUTE.broadcast(new ComponentActionEventArgs(this, value, name));
        wrappedElement.evaluate("(element, [name, value]) => element.setAttribute(name, value)", new String[]{name, value});
        ATTRIBUTE_SET.broadcast(new ComponentActionEventArgs(this));
    }

    @Override
    public boolean isVisible() {
        return findLocator().isVisible();
    }

    // Update: We should use js, native playwright highlight() method's function is unknown.
    // Update: the js script seems to work, but not for TextField elements
    public void highlight() {
        if (PlaywrightService.browserConfiguration().getBrowserTypes() == BrowserTypes.CHROME_HEADLESS) {
            return;
        }

        try {
            var originalElementBackground = wrappedElement.evaluate("el => window.getComputedStyle(el).getPropertyValue('background')");
            var originalElementColor = wrappedElement.evaluate("el => window.getComputedStyle(el).getPropertyValue('color')");
            var originalElementOutline = wrappedElement.evaluate("el => window.getComputedStyle(el).getPropertyValue('outline')");

            String script = "function(el) {" +
                    "    el.style.background='yellow';" +
                    "    el.style.color='black';" +
                    "    el.style.outline='1px solid black';" +
                    "    setTimeout(function() {" +
                    "        el.style.background='" + originalElementBackground + "';" +
                    "        el.style.color='" + originalElementColor + "';" +
                    "        el.style.outline='" + originalElementOutline + "';" +
                    "    }, 100);" +
                    "}";

            findLocator().evaluate(script);
        } catch (Exception ignored) {
        }
    }

    protected String defaultGetValue() {
        return (String) wrappedElement.evaluate("element => String(element.value ?? element.getAttribute('value') ?? '')");
    }

    protected void defaultSetValue(EventListener<ComponentActionEventArgs> gettingValue, EventListener<ComponentActionEventArgs> gotValue, String value) {
        gettingValue.broadcast(new ComponentActionEventArgs(this, value));
        wrappedElement.fill(value);
        gotValue.broadcast(new ComponentActionEventArgs(this, value));
    }

    protected String defaultGetName() {
        return Optional.ofNullable(getAttribute("name")).orElse("");
    }

    protected String defaultGetMaxLength() {
        return Optional.ofNullable(getAttribute("maxlength")).orElse("");
    }

    protected String defaultGetMinLength() {
        return Optional.ofNullable(getAttribute("minlength")).orElse("");
    }

    protected String defaultGetSizeAttribute() {
        return Optional.ofNullable(getAttribute("size")).orElse("");
    }

    protected String defaultGetSizesAttribute() {
        return Optional.ofNullable(getAttribute("sizes")).orElse("");
    }

    protected String defaultGetSrcAttribute() {
        return (String) wrappedElement.evaluate("element => element.src ?? element.getAttribute('src') ?? ''");
    }

    protected String defaultGetSrcSetAttribute() {
        return Optional.ofNullable(getAttribute("srcset")).orElse("");
    }

    protected String defaultGetAltAttribute() {
        return Optional.ofNullable(getAttribute("alt")).orElse("");
    }

    protected String defaultGetColsAttribute() {
        return Optional.ofNullable(getAttribute("cols")).orElse("");
    }

    protected String defaultGetRowsAttribute() {
        return Optional.ofNullable(getAttribute("rows")).orElse("");
    }

    protected String defaultGetLongDescAttribute() {
        return Optional.ofNullable(getAttribute("longdesc")).orElse("");
    }

    protected String defaultGetHeightAttribute() {
        return Optional.ofNullable(getAttribute("height")).orElse("");
    }

    protected String defaultGetWidthAttribute() {
        return Optional.ofNullable(getAttribute("width")).orElse("");
    }

    protected String defaultGetInnerHtmlAttribute() {
        return Optional.ofNullable(findLocator().innerHTML()).orElse("");
    }

    protected String defaultGetForAttribute() {
        return Optional.ofNullable(getAttribute("for")).orElse("");
    }

    protected String defaultGetTargetAttribute() {
        return Optional.ofNullable(getAttribute("target")).orElse("");
    }

    protected String defaultGetRelAttribute() {
        return Optional.ofNullable(getAttribute("rel")).orElse("");
    }

    protected boolean defaultGetDisabledAttribute() {
        return findLocator().isDisabled();
    }

    protected String defaultGetText() {
        return Optional.ofNullable(findLocator().innerText()).orElse("");
    }

    protected String defaultGetEditableText() {
        return (String) findLocator().evaluate("""
                element => element.matches('input, textarea') ? element.value : (element.innerText ?? '')
                """);
    }

    protected String defaultGetMinAttribute() {
        return Optional.ofNullable(getAttribute("min")).orElse("");
    }

    protected String defaultGetMaxAttribute() {
        return Optional.ofNullable(getAttribute("max")).orElse("");
    }

    protected String defaultGetStepAttribute() {
        return Optional.ofNullable(getAttribute("step")).orElse("");
    }

    protected String defaultGetWrapAttribute() {
        return Optional.ofNullable(getAttribute("wrap")).orElse("");
    }

    protected String defaultGetPlaceholderAttribute() {
        return Optional.ofNullable(getAttribute("placeholder")).orElse("");
    }

    protected String defaultGetAcceptAttribute() {
        return getAttribute("accept");
    }

    protected boolean defaultGetAutoCompleteAttribute() {
        return Optional.ofNullable(getAttribute("autocomplete")).orElse("").equals("on");
    }

    protected boolean defaultGetSpellCheckAttribute() {
        return Optional.ofNullable(getAttribute("spellcheck")).orElse("").equals("on");
    }

    protected boolean defaultGetReadonlyAttribute() {
        return getAttribute("readonly") != null;
    }

    protected boolean defaultGetRequiredAttribute() {
        return getAttribute("required") != null;
    }

    protected boolean defaultGetMultipleAttribute() {
        return getAttribute("multiple") != null;
    }

    protected String defaultGetList() {
        return Optional.ofNullable(getAttribute("list")).orElse("");
    }

    @SneakyThrows
    protected String defaultGetHref() {
        var href = (String) wrappedElement.evaluate("element => element.href ?? element.getAttribute('href') ?? ''");
        return unescapeHtml4(URLDecoder.decode(href, StandardCharsets.UTF_8));

    }

    protected void defaultSetText(EventListener<ComponentActionEventArgs> settingValue, EventListener<ComponentActionEventArgs> valueSet, String value) {
        settingValue.broadcast(new ComponentActionEventArgs(this, value));
        if ("number".equalsIgnoreCase(wrappedElement.getAttribute("type"))) {
            wrappedElement.clear();
            wrappedElement.pressSequentially(value);
        } else {
            wrappedElement.fill(value);
        }
        valueSet.broadcast(new ComponentActionEventArgs(this, value));
    }

    protected void defaultSetSensitiveText(EventListener<ComponentActionEventArgs> settingValue,
                                           EventListener<ComponentActionEventArgs> valueSet, String value) {
        Objects.requireNonNull(value, "Sensitive input value is required");
        var masked = new ComponentActionEventArgs(this, "*****");
        settingValue.broadcast(masked);
        try {
            wrappedElement.fill(value);
        } catch (PlaywrightException failure) {
            throw maskInputFailure(failure, value);
        }
        valueSet.broadcast(masked);
    }

    private static PlaywrightException maskInputFailure(PlaywrightException failure, String value) {
        var message = failure.getMessage();
        if (!value.isEmpty() && message != null) {
            message = message.replace(value, "*****")
                    .replace(StringEscapeUtils.escapeHtml4(value), "*****")
                    .replace(StringEscapeUtils.escapeEcmaScript(value), "*****");
        }
        var safeFailure = failure instanceof TimeoutError ? new TimeoutError(message) : new PlaywrightException(message);
        safeFailure.setStackTrace(failure.getStackTrace());
        return safeFailure;
    }

    protected void defaultSelectByText(EventListener<ComponentActionEventArgs> selectingValue, EventListener<ComponentActionEventArgs> valueSelected, String value) {
        selectingValue.broadcast(new ComponentActionEventArgs(this, value));
        wrappedElement.selectOption(new SelectOption().setLabel(value));
        valueSelected.broadcast(new ComponentActionEventArgs(this, value));
    }

    protected void defaultSelectByValue(EventListener<ComponentActionEventArgs> selectingValue, EventListener<ComponentActionEventArgs> valueSelected, String value) {
        selectingValue.broadcast(new ComponentActionEventArgs(this, value));
        wrappedElement.selectOption(new SelectOption().setValue(value));
        valueSelected.broadcast(new ComponentActionEventArgs(this, value));
    }

    protected void defaultSelectByIndex(EventListener<ComponentActionEventArgs> selectingValue, EventListener<ComponentActionEventArgs> valueSelected, int value) {
        selectingValue.broadcast(new ComponentActionEventArgs(this, "index: " + value));
        wrappedElement.selectOption(new SelectOption().setIndex(value));
        valueSelected.broadcast(new ComponentActionEventArgs(this, "index: " + value));
    }

    private void clickInternal(ClickOptions options) {
        if (options == null) {
            // Multiplying by 1000 to get milliseconds
            var toBeClickableTimeout = webSettings.getTimeoutSettings().inMilliseconds().getElementToBeClickableTimeout();
            options = ClickOptions.create(x -> x.setTimeout(toBeClickableTimeout));
        }

        wrappedElement.click(options.convert());
    }

    private void checkInternal(CheckOptions options) {
        if (options == null) {
            // Multiplying by 1000 to get milliseconds
            var toBeCheckableTimeout = webSettings.getTimeoutSettings().inMilliseconds().getElementToBeClickableTimeout();
            options = CheckOptions.create(x -> x.setTimeout(toBeCheckableTimeout));
        }

        wrappedElement.check(options.convert());
    }

    private void uncheckInternal(UncheckOptions options) {
        if (options == null) {
            // Multiplying by 1000 to get milliseconds
            var toBeUncheckableTimeout = webSettings.getTimeoutSettings().inMilliseconds().getElementToBeClickableTimeout();
            options = UncheckOptions.create(x -> x.setTimeout(toBeUncheckableTimeout));
        }

        wrappedElement.uncheck(options.convert());
    }

    protected void defaultClick(ClickOptions options, EventListener<ComponentActionEventArgs> clicking, EventListener<ComponentActionEventArgs> clicked) {
        clicking.broadcast(new ComponentActionEventArgs(this));
        clickInternal(options);
        clicked.broadcast(new ComponentActionEventArgs(this));
    }

    protected void defaultClick(EventListener<ComponentActionEventArgs> clicking, EventListener<ComponentActionEventArgs> clicked) {
        clicking.broadcast(new ComponentActionEventArgs(this));
        clickInternal(null);
        clicked.broadcast(new ComponentActionEventArgs(this));
    }

    protected void defaultCheck(EventListener<ComponentActionEventArgs> clicking, EventListener<ComponentActionEventArgs> clicked) {
        clicking.broadcast(new ComponentActionEventArgs(this));

        checkInternal(null);

        clicked.broadcast(new ComponentActionEventArgs(this));
    }

    protected void defaultCheck(CheckOptions options, EventListener<ComponentActionEventArgs> clicking, EventListener<ComponentActionEventArgs> clicked) {
        clicking.broadcast(new ComponentActionEventArgs(this));

        checkInternal(options);

        clicked.broadcast(new ComponentActionEventArgs(this));
    }

    protected void defaultUncheck(EventListener<ComponentActionEventArgs> checking, EventListener<ComponentActionEventArgs> checked) {
        checking.broadcast(new ComponentActionEventArgs(this));

        uncheckInternal(null);

        checked.broadcast(new ComponentActionEventArgs(this));
    }

    protected void defaultUncheck(UncheckOptions options, EventListener<ComponentActionEventArgs> checking, EventListener<ComponentActionEventArgs> checked) {
        checking.broadcast(new ComponentActionEventArgs(this));

        uncheckInternal(options);

        checked.broadcast(new ComponentActionEventArgs(this));
    }

    public Object evaluate(String script) {
        return this.wrappedElement.evaluate(script);
    }

    public Object evaluate(String script, Object arg) {
        return this.wrappedElement.evaluate(script, arg);
    }

    public Object evaluate(String script, Object arg, int timeoutInSeconds) {
        return this.wrappedElement.evaluate(script, arg, new Locator.EvaluateOptions().setTimeout(timeoutInSeconds * 1000));
    }

    public <TElementType extends WebComponent> TElementType toExist() {
        wrappedElement.waitFor(new Locator.WaitForOptions().setState(WaitForSelectorState.ATTACHED).setTimeout(webSettings.getTimeoutSettings().inMilliseconds().getElementToExistTimeout()));
        return (TElementType)this;
    }

    public <TElementType extends WebComponent> TElementType toShadowRootToBeAttached() {
        wrappedElement.getWrappedLocator().locator("shadow=host").waitFor(
                new Locator.WaitForOptions().setState(WaitForSelectorState.ATTACHED)
                        .setTimeout(webSettings.getTimeoutSettings().inMilliseconds().getElementToExistTimeout()));
        return (TElementType)this;
    }

    public <TElementType extends WebComponent> TElementType toNotExist() {
        wrappedElement.waitFor(new Locator.WaitForOptions().setState(WaitForSelectorState.DETACHED).setTimeout(webSettings.getTimeoutSettings().inMilliseconds().getElementToNotExistTimeout()));
        return (TElementType)this;
    }

    public <TElementType extends WebComponent> TElementType toBeVisible() {
        PlaywrightAssertions.assertThat(wrappedElement.getWrappedLocator()).isVisible(new LocatorAssertions.IsVisibleOptions().setTimeout(webSettings.getTimeoutSettings().inMilliseconds().getElementToBeVisibleTimeout()));
        return (TElementType)this;
    }

    public <TElementType extends WebComponent> TElementType toNotBeVisible() {
        PlaywrightAssertions.assertThat(wrappedElement.getWrappedLocator()).not().isVisible(new LocatorAssertions.IsVisibleOptions().setTimeout(webSettings.getTimeoutSettings().inMilliseconds().getElementNotToBeVisibleTimeout()));
        return (TElementType)this;
    }

    /**
     * Playwright has auto-wait.
     *
     * @deprecated
     */
    @Deprecated
    public <TElementType extends WebComponent> TElementType toBeClickable() {
        wrappedElement.click(new Locator.ClickOptions().setTrial(true)
                .setTimeout(webSettings.getTimeoutSettings().inMilliseconds().getElementToBeClickableTimeout()));
        return (TElementType)this;
    }

    public <TElementType extends WebComponent> TElementType toBeDisabled() {
        PlaywrightAssertions.assertThat(wrappedElement.getWrappedLocator()).isDisabled(new LocatorAssertions.IsDisabledOptions().setTimeout(webSettings.getTimeoutSettings().inMilliseconds().getValidationsTimeout()));
        return (TElementType)this;
    }

    public <TElementType extends WebComponent> TElementType toHaveContent() {
        PlaywrightAssertions.assertThat(wrappedElement.getWrappedLocator()).not().isEmpty(new LocatorAssertions.IsEmptyOptions().setTimeout(webSettings.getTimeoutSettings().inMilliseconds().getElementToHaveContentTimeout()));
        return (TElementType)this;
    }

    public <TElementType extends WebComponent> TElementType toExist(long timeoutIntervalInSeconds, long sleepIntervalInSeconds) {
        wrappedElement.waitFor(new Locator.WaitForOptions().setState(WaitForSelectorState.ATTACHED).setTimeout(timeoutIntervalInSeconds * 1000));
        return (TElementType)this;
    }

    public <TElementType extends WebComponent> TElementType toNotExist(long timeoutIntervalInSeconds, long sleepIntervalInSeconds) {
        wrappedElement.waitFor(new Locator.WaitForOptions().setState(WaitForSelectorState.DETACHED).setTimeout(timeoutIntervalInSeconds * 1000));
        return (TElementType)this;
    }

    public <TElementType extends WebComponent> TElementType toBeVisible(long timeoutIntervalInSeconds, long sleepIntervalInSeconds) {
        PlaywrightAssertions.assertThat(wrappedElement.getWrappedLocator()).isVisible(new LocatorAssertions.IsVisibleOptions().setTimeout(timeoutIntervalInSeconds * 1000));
        return (TElementType)this;
    }

    public <TElementType extends WebComponent> TElementType toNotBeVisible(long timeoutIntervalInSeconds, long sleepIntervalInSeconds) {
        PlaywrightAssertions.assertThat(wrappedElement.getWrappedLocator()).not().isVisible(new LocatorAssertions.IsVisibleOptions().setTimeout(timeoutIntervalInSeconds * 1000));
        return (TElementType)this;
    }

    /**
     * Playwright has auto-wait.
     *
     * @deprecated
     */
    @Deprecated
    public <TElementType extends WebComponent> TElementType toBeClickable(long timeoutIntervalInSeconds, long sleepIntervalInSeconds) {
        wrappedElement.click(new Locator.ClickOptions().setTrial(true).setTimeout(timeoutIntervalInSeconds * 1000));
        return (TElementType)this;
    }

    public <TElementType extends WebComponent> TElementType toBeDisabled(long timeoutIntervalInSeconds, long sleepIntervalInSeconds) {
        PlaywrightAssertions.assertThat(wrappedElement.getWrappedLocator()).isDisabled(new LocatorAssertions.IsDisabledOptions().setTimeout(timeoutIntervalInSeconds * 1000));
        return (TElementType)this;
    }

    public <TElementType extends WebComponent> TElementType toHaveContent(long timeoutIntervalInSeconds, long sleepIntervalInSeconds) {
        PlaywrightAssertions.assertThat(wrappedElement.getWrappedLocator()).not().isEmpty(new LocatorAssertions.IsEmptyOptions().setTimeout(timeoutIntervalInSeconds * 1000));
        return (TElementType)this;
    }
    
    @Deprecated
    public <TElementType extends WebComponent> TElementType waitToBe() {
        return toExist();
    }

    // createBy methods

    /**
     * Use {@link WebComponent#create() } instead
     */
    @Deprecated
    public <TComponent extends WebComponent> TComponent createByName(Class<TComponent> componentClass, String name) {
        return create().by(componentClass, new NameFindStrategy(name));
    }

    /**
     * Use {@link WebComponent#create() } instead
     */
    @Deprecated
    public <TComponent extends WebComponent> List<TComponent> createAllByName(Class<TComponent> componentClass, String name) {
        return create().allBy(componentClass, new NameFindStrategy(name));
    }

    /**
     * Use {@link WebComponent#create() } instead
     */
    @Deprecated
    public <TComponent extends WebComponent> TComponent createByNameEndingWith(Class<TComponent> componentClass, String value) {
        return create().by(componentClass, new NameEndingWithFindStrategy(value));
    }

    /**
     * Use {@link WebComponent#create() } instead
     */
    @Deprecated
    public <TComponent extends WebComponent> List<TComponent> createAllByNameEndingWith(Class<TComponent> componentClass, String value) {
        return create().allBy(componentClass, new NameEndingWithFindStrategy(value));
    }

    /**
     * Use {@link WebComponent#create() } instead
     */
    @Deprecated
    public <TComponent extends WebComponent> TComponent createByTag(Class<TComponent> componentClass, String tag) {
        return create().by(componentClass, new TagFindStrategy(tag));
    }

    /**
     * Use {@link WebComponent#create() } instead
     */
    @Deprecated
    public <TComponent extends WebComponent> List<TComponent> createAllByTag(Class<TComponent> componentClass, String tag) {
        return create().allBy(componentClass, new TagFindStrategy(tag));
    }

    /**
     * Use {@link WebComponent#create() } instead
     */
    @Deprecated
    public <TComponent extends WebComponent> TComponent createByValueContaining(Class<TComponent> componentClass, String value) {
        return create().by(componentClass, new ValueContainingFindStrategy(value));
    }

    /**
     * Use {@link WebComponent#create() } instead
     */
    @Deprecated
    public <TComponent extends WebComponent> List<TComponent> createAllByValueContaining(Class<TComponent> componentClass, String value) {
        return create().allBy(componentClass, new ValueContainingFindStrategy(value));
    }

    /**
     * Use {@link WebComponent#create() } instead
     */
    @Deprecated
    public <TComponent extends WebComponent> TComponent createByLinkText(Class<TComponent> componentClass, String text) {
        return create().by(componentClass, new LinkTextFindStrategy(text));
    }

    /**
     * Use {@link WebComponent#create() } instead
     */
    @Deprecated
    public <TComponent extends WebComponent> List<TComponent> createAllByLinkText(Class<TComponent> componentClass, String text) {
        return create().allBy(componentClass, new LinkTextFindStrategy(text));
    }

    /**
     * Use {@link WebComponent#create() } instead
     */
    @Deprecated
    public <TComponent extends WebComponent> TComponent createByLinkTextContaining(Class<TComponent> componentClass, String text) {
        return create().by(componentClass, new LinkTextContainingFindStrategy(text));
    }

    /**
     * Use {@link WebComponent#create() } instead
     */
    @Deprecated
    public <TComponent extends WebComponent> List<TComponent> createAllByLinkTextContaining(Class<TComponent> componentClass, String text) {
        return create().allBy(componentClass, new LinkTextContainingFindStrategy(text));
    }

    /**
     * Use {@link WebComponent#create() } instead
     */
    @Deprecated
    public <TComponent extends WebComponent> TComponent createByIdEndingWith(Class<TComponent> componentClass, String value) {
        return create().by(componentClass, new IdEndingWithFindStrategy(value));
    }

    /**
     * Use {@link WebComponent#create() } instead
     */
    @Deprecated
    public <TComponent extends WebComponent> List<TComponent> createAllByIdEndingWith(Class<TComponent> componentClass, String value) {
        return create().allBy(componentClass, new IdEndingWithFindStrategy(value));
    }

    /**
     * Use {@link WebComponent#create() } instead
     */
    @Deprecated
    public <TComponent extends WebComponent> TComponent createByPlaceholder(Class<TComponent> componentClass, String text) {
        return create().byPlaceholder(componentClass, text, null);
    }

    /**
     * Use {@link WebComponent#create() } instead
     */
    @Deprecated
    public <TComponent extends WebComponent> List<TComponent> createAllByPlaceholder(Class<TComponent> componentClass, String text) {
        return create().allByPlaceholder(componentClass, text, null);
    }

    /**
     * Use {@link WebComponent#create() } instead
     */
    @Deprecated
    public <TComponent extends WebComponent> TComponent createByPlaceholder(Class<TComponent> componentClass, Pattern pattern) {
        return create().byPlaceholder(componentClass, pattern, null);
    }

    /**
     * Use {@link WebComponent#create() } instead
     */
    @Deprecated
    public <TComponent extends WebComponent> List<TComponent> createAllByPlaceholder(Class<TComponent> componentClass, Pattern pattern) {
        return create().allByPlaceholder(componentClass, pattern, null);
    }

    /**
     * Use {@link WebComponent#create() } instead
     */
    @Deprecated
    public <TComponent extends WebComponent> TComponent createByRole(Class<TComponent> componentClass, AriaRole role) {
        return create().byRole(componentClass, role, null);
    }

    /**
     * Use {@link WebComponent#create() } instead
     */
    @Deprecated
    public <TComponent extends WebComponent> List<TComponent> createAllByRole(Class<TComponent> componentClass, AriaRole role) {
        return create().allByRole(componentClass, role, null);
    }

    /**
     * Use {@link WebComponent#create() } instead
     */
    @Deprecated
    public <TComponent extends WebComponent> TComponent createByAltText(Class<TComponent> componentClass, String text) {
        return create().byAltText(componentClass, text, null);
    }

    /**
     * Use {@link WebComponent#create() } instead
     */
    @Deprecated
    public <TComponent extends WebComponent> List<TComponent> createAllByAltText(Class<TComponent> componentClass, String text) {
        return create().allByAltText(componentClass, text, null);
    }

    /**
     * Use {@link WebComponent#create() } instead
     */
    @Deprecated
    public <TComponent extends WebComponent> TComponent createByAltText(Class<TComponent> componentClass, Pattern pattern) {
        return create().byAltText(componentClass, pattern, null);
    }

    /**
     * Use {@link WebComponent#create() } instead
     */
    @Deprecated
    public <TComponent extends WebComponent> List<TComponent> createAllByAltText(Class<TComponent> componentClass, Pattern pattern) {
        return create().allByAltText(componentClass, pattern, null);
    }

    /**
     * Use {@link WebComponent#create() } instead
     */
    @Deprecated
    public <TComponent extends WebComponent> TComponent createByTextContaining(Class<TComponent> componentClass, String text) {
        return create().byInnerTextContaining(componentClass, text);
    }

    /**
     * Use {@link WebComponent#create() } instead
     */
    @Deprecated
    public <TComponent extends WebComponent> List<TComponent> createAllByTextContaining(Class<TComponent> componentClass, String text) {
        return create().allByInnerTextContaining(componentClass, text);
    }

    public <TComponent extends WebComponent> TComponent createByInnerTextContaining(Class<TComponent> componentClass, String text) {
        return create().byInnerTextContaining(componentClass, text);
    }

    public <TComponent extends WebComponent> List<TComponent> createAllByInnerTextContaining(Class<TComponent> componentClass, String text) {
        return create().allByInnerTextContaining(componentClass, text);
    }

    /**
     * Use {@link WebComponent#create() } instead
     */
    @Deprecated
    public <TComponent extends WebComponent> TComponent createByTitle(Class<TComponent> componentClass, String text) {
        return create().byTitle(componentClass, text, null);
    }

    /**
     * Use {@link WebComponent#create() } instead
     */
    @Deprecated
    public <TComponent extends WebComponent> List<TComponent> createAllByTitle(Class<TComponent> componentClass, String text) {
        return create().allByTitle(componentClass, text, null);
    }

    /**
     * Use {@link WebComponent#create() } instead
     */
    @Deprecated
    public <TComponent extends WebComponent> TComponent createByTitle(Class<TComponent> componentClass, Pattern pattern) {
        return create().byTitle(componentClass, pattern, null);
    }

    /**
     * Use {@link WebComponent#create() } instead
     */
    @Deprecated
    public <TComponent extends WebComponent> List<TComponent> createAllByTitle(Class<TComponent> componentClass, Pattern pattern) {
        return create().allByTitle(componentClass, pattern, null);
    }

    /**
     * Use {@link WebComponent#create() } instead
     */
    @Deprecated
    public <TComponent extends WebComponent> TComponent createByIdContaining(Class<TComponent> componentClass, String value) {
        return create().by(componentClass, new IdContainingFindStrategy(value));
    }

    /**
     * Use {@link WebComponent#create() } instead
     */
    @Deprecated
    public <TComponent extends WebComponent> List<TComponent> createAllByIdContaining(Class<TComponent> componentClass, String value) {
        return create().allBy(componentClass, new IdContainingFindStrategy(value));
    }

    /**
     * Use {@link WebComponent#create() } instead
     */
    @Deprecated
    public <TComponent extends WebComponent> TComponent createById(Class<TComponent> componentClass, String value) {
        return create().by(componentClass, new IdFindStrategy(value));
    }

    /**
     * Use {@link WebComponent#create() } instead
     */
    @Deprecated
    public <TComponent extends WebComponent> List<TComponent> createAllById(Class<TComponent> componentClass, String value) {
        return create().allBy(componentClass, new IdFindStrategy(value));
    }

    /**
     * Use {@link WebComponent#create() } instead
     */
    @Deprecated
    public <TComponent extends WebComponent> TComponent createByClass(Class<TComponent> componentClass, String value) {
        return create().by(componentClass, new ClassFindStrategy(value));
    }

    /**
     * Use {@link WebComponent#create() } instead
     */
    @Deprecated
    public <TComponent extends WebComponent> List<TComponent> createAllByClass(Class<TComponent> componentClass, String value) {
        return create().allBy(componentClass, new ClassFindStrategy(value));
    }

    /**
     * Use {@link WebComponent#create() } instead
     */
    @Deprecated
    public <TComponent extends WebComponent> TComponent createByClassContaining(Class<TComponent> componentClass, String value) {
        return create().by(componentClass, new ClassContainingFindStrategy(value));
    }

    /**
     * Use {@link WebComponent#create() } instead
     */
    @Deprecated
    public <TComponent extends WebComponent> List<TComponent> createAllByClassContaining(Class<TComponent> componentClass, String value) {
        return create().allBy(componentClass, new ClassContainingFindStrategy(value));
    }

    /**
     * Use {@link WebComponent#create() } instead
     */
    @Deprecated
    public <TComponent extends WebComponent> TComponent createByAttributeContaining(Class<TComponent> componentClass, String attributeName, String value) {
        return create().by(componentClass, new AttributeContainingFindStrategy(attributeName, value));
    }

    /**
     * Use {@link WebComponent#create() } instead
     */
    @Deprecated
    public <TComponent extends WebComponent> List<TComponent> createAllByAttributeContaining(Class<TComponent> componentClass, String attributeName, String value) {
        return create().allBy(componentClass, new AttributeContainingFindStrategy(attributeName, value));
    }

    /**
     * Use {@link WebComponent#create() } instead
     */
    @Deprecated
    public <TComponent extends WebComponent> TComponent createByPlaceholder(Class<TComponent> componentClass, String text, Function<Page.GetByPlaceholderOptions, Page.GetByPlaceholderOptions> options) {
        var placeholderOptions = PlaceholderOptions.createAbsolute(options);
        return create().by(componentClass, new PlaceholderFindStrategy(text, placeholderOptions));
    }

    /**
     * Use {@link WebComponent#create() } instead
     */
    @Deprecated
    public <TComponent extends WebComponent> List<TComponent> createAllByPlaceholder(Class<TComponent> componentClass, String text, Function<Page.GetByPlaceholderOptions, Page.GetByPlaceholderOptions> options) {
        var placeholderOptions = PlaceholderOptions.createAbsolute(options);
        return create().allBy(componentClass, new PlaceholderFindStrategy(text, placeholderOptions));
    }

    /**
     * Use {@link WebComponent#create() } instead
     */
    @Deprecated
    public <TComponent extends WebComponent> TComponent createByPlaceholder(Class<TComponent> componentClass, Pattern pattern, Function<Page.GetByPlaceholderOptions, Page.GetByPlaceholderOptions> options) {
        var placeholderOptions = PlaceholderOptions.createAbsolute(options);
        return create().by(componentClass, new PlaceholderFindStrategy(pattern, placeholderOptions));
    }

    /**
     * Use {@link WebComponent#create() } instead
     */
    @Deprecated
    public <TComponent extends WebComponent> List<TComponent> createAllByPlaceholder(Class<TComponent> componentClass, Pattern pattern, Function<Page.GetByPlaceholderOptions, Page.GetByPlaceholderOptions> options) {
        var placeholderOptions = PlaceholderOptions.createAbsolute(options);
        return create().allBy(componentClass, new PlaceholderFindStrategy(pattern, placeholderOptions));
    }

    /**
     * Use {@link WebComponent#create() } instead
     */
    @Deprecated
    public <TComponent extends WebComponent> TComponent createByTestId(Class<TComponent> componentClass, String text) {
        return create().by(componentClass, new TestIdFindStrategy(text));
    }

    /**
     * Use {@link WebComponent#create() } instead
     */
    @Deprecated
    public <TComponent extends WebComponent> List<TComponent> createAllByTestId(Class<TComponent> componentClass, String text) {
        return create().allBy(componentClass, new TestIdFindStrategy(text));
    }

    /**
     * Use {@link WebComponent#create() } instead
     */
    @Deprecated
    public <TComponent extends WebComponent> TComponent createByTestId(Class<TComponent> componentClass, Pattern pattern) {
        return create().by(componentClass, new TestIdFindStrategy(pattern));
    }

    /**
     * Use {@link WebComponent#create() } instead
     */
    @Deprecated
    public <TComponent extends WebComponent> List<TComponent> createAllByTestId(Class<TComponent> componentClass, Pattern pattern) {
        return create().allBy(componentClass, new TestIdFindStrategy(pattern));
    }

    /**
     * Use {@link WebComponent#create() } instead
     */
    @Deprecated
    public <TComponent extends WebComponent> TComponent createByRole(Class<TComponent> componentClass, AriaRole role, Function<Page.GetByRoleOptions, Page.GetByRoleOptions> options) {
        var roleOptions = RoleOptions.createAbsolute(options);
        return create().by(componentClass, new AriaRoleFindStrategy(role, roleOptions));
    }

    /**
     * Use {@link WebComponent#create() } instead
     */
    @Deprecated
    public <TComponent extends WebComponent> List<TComponent> createAllByRole(Class<TComponent> componentClass, AriaRole role, Function<Page.GetByRoleOptions, Page.GetByRoleOptions> options) {
        var roleOptions = RoleOptions.createAbsolute(options);
        return create().allBy(componentClass, new AriaRoleFindStrategy(role, roleOptions));
    }

    /**
     * Use {@link WebComponent#create() } instead
     */
    @Deprecated
    public <TComponent extends WebComponent> TComponent createByAltText(Class<TComponent> componentClass, String text, Function<Page.GetByAltTextOptions, Page.GetByAltTextOptions> options) {
        var altTextOptions = AltTextOptions.createAbsolute(options);
        return create().by(componentClass, new AltTextFindStrategy(text, altTextOptions));
    }

    /**
     * Use {@link WebComponent#create() } instead
     */
    @Deprecated
    public <TComponent extends WebComponent> List<TComponent> createAllByAltText(Class<TComponent> componentClass, String text, Function<Page.GetByAltTextOptions, Page.GetByAltTextOptions> options) {
        var altTextOptions = AltTextOptions.createAbsolute(options);
        return create().allBy(componentClass, new AltTextFindStrategy(text, altTextOptions));
    }

    /**
     * Use {@link WebComponent#create() } instead
     */
    @Deprecated
    public <TComponent extends WebComponent> TComponent createByAltText(Class<TComponent> componentClass, Pattern pattern, Function<Page.GetByAltTextOptions, Page.GetByAltTextOptions> options) {
        var altTextOptions = AltTextOptions.createAbsolute(options);
        return create().by(componentClass, new AltTextFindStrategy(pattern, altTextOptions));
    }

    /**
     * Use {@link WebComponent#create() } instead
     */
    @Deprecated
    public <TComponent extends WebComponent> List<TComponent> createAllByAltText(Class<TComponent> componentClass, Pattern pattern, Function<Page.GetByAltTextOptions, Page.GetByAltTextOptions> options) {
        var altTextOptions = AltTextOptions.createAbsolute(options);
        return create().allBy(componentClass, new AltTextFindStrategy(pattern, altTextOptions));
    }

    /**
     * Use {@link WebComponent#create() } instead
     */
    @Deprecated
    public <TComponent extends WebComponent> TComponent createByTitle(Class<TComponent> componentClass, String text, Function<Page.GetByTitleOptions, Page.GetByTitleOptions> options) {
        var titleOptions = TitleOptions.createAbsolute(options);
        return create().by(componentClass, new TitleFindStrategy(text, titleOptions));
    }

    /**
     * Use {@link WebComponent#create() } instead
     */
    @Deprecated
    public <TComponent extends WebComponent> List<TComponent> createAllByTitle(Class<TComponent> componentClass, String text, Function<Page.GetByTitleOptions, Page.GetByTitleOptions> options) {
        var titleOptions = TitleOptions.createAbsolute(options);
        return create().allBy(componentClass, new TitleFindStrategy(text, titleOptions));
    }

    /**
     * Use {@link WebComponent#create() } instead
     */
    @Deprecated
    public <TComponent extends WebComponent> TComponent createByTitle(Class<TComponent> componentClass, Pattern pattern, Function<Page.GetByTitleOptions, Page.GetByTitleOptions> options) {
        var titleOptions = TitleOptions.createAbsolute(options);
        return create().by(componentClass, new TitleFindStrategy(pattern, titleOptions));
    }

    /**
     * Use {@link WebComponent#create() } instead
     */
    @Deprecated
    public <TComponent extends WebComponent> List<TComponent> createAllByTitle(Class<TComponent> componentClass, Pattern pattern, Function<Page.GetByTitleOptions, Page.GetByTitleOptions> options) {
        var titleOptions = TitleOptions.createAbsolute(options);
        return create().allBy(componentClass, new TitleFindStrategy(pattern, titleOptions));
    }

    /**
     * Use {@link WebComponent#create() } instead
     */
    @Deprecated
    public <TComponent extends WebComponent> TComponent createByXpath(Class<TComponent> componentClass, String xpath) {
        return create().by(componentClass, new XpathFindStrategy(xpath));
    }

    /**
     * Use {@link WebComponent#create() } instead
     */
    @Deprecated
    public <TComponent extends WebComponent> List<TComponent> createAllByXpath(Class<TComponent> componentClass, String xpath) {
        return create().allBy(componentClass, new XpathFindStrategy(xpath));
    }

    public <TComponent extends WebComponent> TComponent createByXPath(Class<TComponent> componentClass, String xpath) {
        return create().byXPath(componentClass, xpath);
    }

    public <TComponent extends WebComponent> List<TComponent> createAllByXPath(Class<TComponent> componentClass, String xpath) {
        return create().allByXPath(componentClass, xpath);
    }

    /**
     * Use {@link WebComponent#create() } instead
     */
    @Deprecated
    public <TComponent extends WebComponent> TComponent createByCss(Class<TComponent> componentClass, String css) {
        return create().by(componentClass, new CssFindStrategy(css));
    }

    /**
     * Use {@link WebComponent#create() } instead
     */
    @Deprecated
    public <TComponent extends WebComponent> List<TComponent> createAllByCss(Class<TComponent> componentClass, String css) {
        return create().allBy(componentClass, new CssFindStrategy(css));
    }

    /**
     * Use {@link WebComponent#getShadowRoot() } instead
     */
    @Deprecated
    public <TComponent extends WebComponent, TFindStrategy extends FindStrategy> TComponent shadowRootCreate(Class<TFindStrategy> findStrategyClass, Class<TComponent> componentClass, Object... args) {
        var findStrategy = InstanceFactory.create(findStrategyClass, args);
        return getShadowRoot().create().by(componentClass, findStrategy);
    }

    /**
     * Use {@link WebComponent#getShadowRoot() } instead
     */
    @Deprecated
    public <TComponent extends WebComponent, TFindStrategy extends FindStrategy> List<TComponent> shadowRootCreateAll(Class<TFindStrategy> findStrategyClass, Class<TComponent> componentClass, Object... args) {
        var findStrategy = InstanceFactory.create(findStrategyClass, args);
        return getShadowRoot().create().allBy(componentClass, findStrategy);
    }

    /**
     * Use {@link WebComponent#getShadowRoot() } instead
     */
    @Deprecated
    public <TComponent extends WebComponent> TComponent shadowRootCreateById(Class<TComponent> componentClass, String id) {
        return getShadowRoot().create().by(componentClass, new IdFindStrategy(id));
    }

    /**
     * Use {@link WebComponent#getShadowRoot() } instead
     */
    @Deprecated
    public <TComponent extends WebComponent> TComponent shadowRootCreateByAttributeContaining(Class<TComponent> componentClass, String attributeName, String value) {
        return getShadowRoot().create().by(componentClass, new AttributeContainingFindStrategy(attributeName, value));
    }

    /**
     * Use {@link WebComponent#getShadowRoot() } instead
     */
    @Deprecated
    public <TComponent extends WebComponent> TComponent shadowRootCreateByIdEndingWith(Class<TComponent> componentClass, String idEnding) {
        return getShadowRoot().create().by(componentClass, new IdEndingWithFindStrategy(idEnding));
    }

    /**
     * Use {@link WebComponent#getShadowRoot() } instead
     */
    @Deprecated
    public <TComponent extends WebComponent> TComponent shadowRootCreateByCss(Class<TComponent> componentClass, String css) {
        return getShadowRoot().create().by(componentClass, new CssFindStrategy(css));
    }

    /**
     * Use {@link WebComponent#getShadowRoot() } instead
     */
    @Deprecated
    public <TComponent extends WebComponent> TComponent shadowRootCreateByClass(Class<TComponent> componentClass, String className) {
        return getShadowRoot().create().by(componentClass, new ClassFindStrategy(className));
    }

    /**
     * Use {@link WebComponent#getShadowRoot() } instead
     */
    @Deprecated
    public <TComponent extends WebComponent> TComponent shadowRootCreateByName(Class<TComponent> componentClass, String name) {
        return getShadowRoot().create().by(componentClass, new NameFindStrategy(name));
    }

    /**
     * Use {@link WebComponent#getShadowRoot() } instead
     */
    @Deprecated
    public <TComponent extends WebComponent> TComponent shadowRootCreateByNameEnding(Class<TComponent> componentClass, String nameEnding) {
        return getShadowRoot().create().by(componentClass, new NameEndingWithFindStrategy(nameEnding));
    }

    /**
     * Use {@link WebComponent#getShadowRoot() } instead
     */
    @Deprecated
    public <TComponent extends WebComponent> TComponent shadowRootCreateByValueContaining(Class<TComponent> componentClass, String valueContaining) {
        return getShadowRoot().create().by(componentClass, new ValueContainingFindStrategy(valueContaining));
    }

    /**
     * Use {@link WebComponent#getShadowRoot() } instead
     */
    @Deprecated
    public <TComponent extends WebComponent> TComponent shadowRootCreateByClassContaining(Class<TComponent> componentClass, String classNameContaining) {
        return getShadowRoot().create().by(componentClass, new ClassContainingFindStrategy(classNameContaining));
    }

    /**
     * Use {@link WebComponent#getShadowRoot() } instead
     */
    @Deprecated
    public <TComponent extends WebComponent> TComponent shadowRootCreateByXPath(Class<TComponent> componentClass, String xpath) {
        return getShadowRoot().create().by(componentClass, new XpathFindStrategy(xpath));
    }

    /**
     * Use {@link WebComponent#getShadowRoot() } instead
     */
    @Deprecated
    public <TComponent extends WebComponent> TComponent shadowRootCreateByLinkText(Class<TComponent> componentClass, String linkText) {
        return getShadowRoot().create().by(componentClass, new LinkTextFindStrategy(linkText));
    }

    /**
     * Use {@link WebComponent#getShadowRoot() } instead
     */
    @Deprecated
    public <TComponent extends WebComponent> TComponent shadowRootCreateByLinkTextContaining(Class<TComponent> componentClass, String linkTextContaining) {
        return getShadowRoot().create().by(componentClass, new LinkTextContainingFindStrategy(linkTextContaining));
    }

    /**
     * Use {@link WebComponent#getShadowRoot() } instead
     */
    @Deprecated
    public <TComponent extends WebComponent> TComponent shadowRootCreateByTag(Class<TComponent> componentClass, String tag) {
        return getShadowRoot().create().by(componentClass, new TagFindStrategy(tag));
    }

    /**
     * Use {@link WebComponent#getShadowRoot() } instead
     */
    @Deprecated
    public <TComponent extends WebComponent> TComponent shadowRootCreateByIdContaining(Class<TComponent> componentClass, String idContaining) {
        return getShadowRoot().create().by(componentClass, new IdContainingFindStrategy(idContaining));
    }

    /**
     * Use {@link WebComponent#getShadowRoot() } instead
     */
    @Deprecated
    public <TComponent extends WebComponent> TComponent shadowRootCreateByInnerTextContaining(Class<TComponent> componentClass, String innerText) {
        return getShadowRoot().create().by(componentClass, new InnerTextContainingFindStrategy(innerText));
    }

    /**
     * Use {@link WebComponent#getShadowRoot() } instead
     */
    @Deprecated
    public <TComponent extends WebComponent> List<TComponent> shadowRootCreateAllById(Class<TComponent> componentClass, String id) {
        return getShadowRoot().create().allBy(componentClass, new IdFindStrategy(id));
    }

    /**
     * Use {@link WebComponent#getShadowRoot() } instead
     */
    @Deprecated
    public <TComponent extends WebComponent> List<TComponent> shadowRootCreateAllByAttributeContaining(Class<TComponent> componentClass, String attributeName, String value) {
        return getShadowRoot().create().allBy(componentClass, new AttributeContainingFindStrategy(attributeName, value));
    }

    /**
     * Use {@link WebComponent#getShadowRoot() } instead
     */
    @Deprecated
    public <TComponent extends WebComponent> List<TComponent> shadowRootCreateAllByIdEndingWith(Class<TComponent> componentClass, String idEnding) {
        return getShadowRoot().create().allBy(componentClass, new IdEndingWithFindStrategy(idEnding));
    }

    /**
     * Use {@link WebComponent#getShadowRoot() } instead
     */
    @Deprecated
    public <TComponent extends WebComponent> List<TComponent> shadowRootCreateAllByCss(Class<TComponent> componentClass, String css) {
        return getShadowRoot().create().allBy(componentClass, new CssFindStrategy(css));
    }

    /**
     * Use {@link WebComponent#getShadowRoot() } instead
     */
    @Deprecated
    public <TComponent extends WebComponent> List<TComponent> shadowRootCreateAllByClass(Class<TComponent> componentClass, String className) {
        return getShadowRoot().create().allBy(componentClass, new ClassFindStrategy(className));
    }

    /**
     * Use {@link WebComponent#getShadowRoot() } instead
     */
    @Deprecated
    public <TComponent extends WebComponent> List<TComponent> shadowRootCreateAllByName(Class<TComponent> componentClass, String name) {
        return getShadowRoot().create().allBy(componentClass, new NameFindStrategy(name));
    }

    /**
     * Use {@link WebComponent#getShadowRoot() } instead
     */
    @Deprecated
    public <TComponent extends WebComponent> List<TComponent> shadowRootCreateAllByNameEnding(Class<TComponent> componentClass, String nameEnding) {
        return getShadowRoot().create().allBy(componentClass, new NameEndingWithFindStrategy(nameEnding));
    }

    /**
     * Use {@link WebComponent#getShadowRoot() } instead
     */
    @Deprecated
    public <TComponent extends WebComponent> List<TComponent> shadowRootCreateAllByValueContaining(Class<TComponent> componentClass, String valueContaining) {
        return getShadowRoot().create().allBy(componentClass, new ValueContainingFindStrategy(valueContaining));
    }

    /**
     * Use {@link WebComponent#getShadowRoot() } instead
     */
    @Deprecated
    public <TComponent extends WebComponent> List<TComponent> shadowRootCreateAllByClassContaining(Class<TComponent> componentClass, String classNameContaining) {
        return getShadowRoot().create().allBy(componentClass, new ClassContainingFindStrategy(classNameContaining));
    }

    /**
     * Use {@link WebComponent#getShadowRoot() } instead
     */
    @Deprecated
    public <TComponent extends WebComponent> List<TComponent> shadowRootCreateAllByXPath(Class<TComponent> componentClass, String xpath) {
        return getShadowRoot().create().allBy(componentClass, new XpathFindStrategy(xpath));
    }

    /**
     * Use {@link WebComponent#getShadowRoot() } instead
     */
    @Deprecated
    public <TComponent extends WebComponent> List<TComponent> shadowRootCreateAllByLinkText(Class<TComponent> componentClass, String linkText) {
        return getShadowRoot().create().allBy(componentClass, new LinkTextFindStrategy(linkText));
    }

    /**
     * Use {@link WebComponent#getShadowRoot() } instead
     */
    @Deprecated
    public <TComponent extends WebComponent> List<TComponent> shadowRootCreateAllByLinkTextContaining(Class<TComponent> componentClass, String linkTextContaining) {
        return getShadowRoot().create().allBy(componentClass, new LinkTextContainingFindStrategy(linkTextContaining));
    }

    /**
     * Use {@link WebComponent#getShadowRoot() } instead
     */
    @Deprecated
    public <TComponent extends WebComponent> List<TComponent> shadowRootCreateAllByTag(Class<TComponent> componentClass, String tag) {
        return getShadowRoot().create().allBy(componentClass, new TagFindStrategy(tag));
    }

    /**
     * Use {@link WebComponent#getShadowRoot() } instead
     */
    @Deprecated
    public <TComponent extends WebComponent> List<TComponent> shadowRootCreateAllByIdContaining(Class<TComponent> componentClass, String idContaining) {
        return getShadowRoot().create().allBy(componentClass, new IdContainingFindStrategy(idContaining));
    }

    /**
     * Use {@link WebComponent#getShadowRoot() } instead
     */
    @Deprecated
    public <TComponent extends WebComponent> List<TComponent> shadowRootCreateAllByInnerTextContaining(Class<TComponent> componentClass, String innerText) {
        return getShadowRoot().create().allBy(componentClass, new InnerTextContainingFindStrategy(innerText));
    }
}
