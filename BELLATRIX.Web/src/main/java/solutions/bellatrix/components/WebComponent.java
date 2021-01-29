/*
 * Copyright 2021 Automate The Planet Ltd.
 * Author: Anton Angelov
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0
 * Unless required createBy applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package solutions.bellatrix.components;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.ExtensionMethod;
import org.openqa.selenium.*;
import solutions.bellatrix.components.contracts.Component;
import solutions.bellatrix.configuration.ConfigurationService;
import solutions.bellatrix.configuration.WebSettings;
import solutions.bellatrix.findstrategies.*;
import solutions.bellatrix.infrastructure.DriverService;
import solutions.bellatrix.plugins.EventListener;
import solutions.bellatrix.services.BrowserService;
import solutions.bellatrix.services.ComponentCreateService;
import solutions.bellatrix.services.ComponentWaitService;
import solutions.bellatrix.services.JavaScriptService;
import solutions.bellatrix.utilities.InstanceFactory;
import solutions.bellatrix.waitstrategies.*;

import java.util.ArrayList;
import java.util.List;

@ExtensionMethod({WebComponent.class, WaitStrategyElementsExtensions.class})
public class WebComponent implements Component {
    private WebSettings webSettings;
    @Getter  @Setter(AccessLevel.PROTECTED) private WebElement wrappedElement;
    @Getter @Setter private WebElement parentWrappedElement;
    @Getter @Setter private int elementIndex;
    @Getter @Setter private FindStrategy findStrategy;
    @Getter private WebDriver wrappedDriver;

    // TODO: set elementName and pageName
//    @Getter private String elementName;
//    @Getter private String pageName;

    private List<WaitStrategy> waitStrategies;
    @Getter protected JavaScriptService javaScriptService;
    @Getter protected BrowserService browserService;
    @Getter protected ComponentCreateService componentCreateService;
    @Getter protected ComponentWaitService componentWaitService;

    public WebComponent() {
        this.waitStrategies = new ArrayList<>();
        webSettings = ConfigurationService.get(WebSettings.class);
        javaScriptService = new JavaScriptService();
        browserService = new BrowserService();
        componentCreateService = new ComponentCreateService();
        componentWaitService = new ComponentWaitService();
        wrappedDriver = DriverService.getWrappedDriver();
    }

    public String getElementName() {
        return String.format("%s (%s)", getComponentClass().getSimpleName(), findStrategy.toString());
    }

//    public WebComponent(FindStrategy findStrategy) {
//        this(findStrategy, 0, null);
//    }
//
//    public WebComponent(FindStrategy createBy, int elementIndex, WebElement parentWrappedElement) {
//        this.parentWrappedElement = parentWrappedElement;
//        this.elementIndex = elementIndex;
//        this.findStrategy = findStrategy;
//        this.waitStrategies = new ArrayList<>();
//        webSettings = ConfigurationService.get(WebSettings.class);
//    }

    public void waitToBe() {
        findElement();
    }

    public void scrollToVisible() {
        scrollToVisible(findElement(), false);
    }

    public void setAttribute(String name, String value) {
//        SettingAttribute?.Invoke(this, new ElementActionEventArgs(this));
        javaScriptService.execute(String.format("arguments[0].setAttribute('%s', '%s');", name, value), this);

//        AttributeSet?.Invoke(this, new ElementActionEventArgs(this));
    }

    public void focus() {
//        Focusing?.Invoke(this, new ElementActionEventArgs(this));
        javaScriptService.execute("window.focus();");
        javaScriptService.execute("arguments[0].focus();", findElement());
//        Focused?.Invoke(this, new ElementActionEventArgs(this));
    }

    public void hover() {
//        Focusing?.Invoke(this, new ElementActionEventArgs(this));
        javaScriptService.execute("arguments[0].onmouseover();", findElement());
//        Focused?.Invoke(this, new ElementActionEventArgs(this));
    }

    public Class<?> getComponentClass() {
        return getClass();
    }

    public Point getLocation() {
        return findElement().getLocation();
    }

    public Dimension getSize() {
        return findElement().getSize();
    }

    public String getTitle() {
        return getAttribute("title");
    }

    public String getTabIndex() {
        return getAttribute("tabindex");
    }

    public String getAccessKey() {
        return getAttribute("accesskey");
    }

    public String getStyle() {
        return getAttribute("style");
    }

    public String getDir() {
        return getAttribute("dir");
    }

    public String getLang() {
        return getAttribute("lang");
    }

    public String getHtmlClass() {
        return getAttribute("class");
    }

    public String getAttribute(String name) {
        return findElement().getAttribute(name);
    }

    public String getCssValue(String propertyName) {
        return findElement().getCssValue(propertyName);
    }

    public void ensureState(WaitStrategy waitStrategy) {
        waitStrategies.add(waitStrategy);
    }

    public <TElementType extends WebComponent> TElementType toExists() {
        var waitStrategy = new ToExistsWaitStrategy();
        ensureState(waitStrategy);
        return (TElementType)this;
    }

    public <TElementType extends WebComponent> TElementType toBeClickable() {
        var waitStrategy = new ToBeClickableWaitStrategy();
        ensureState(waitStrategy);
        return (TElementType)this;
    }

    public <TElementType extends WebComponent> TElementType toBeVisible() {
        var waitStrategy = new ToBeVisibleWaitStrategy();
        ensureState(waitStrategy);
        return (TElementType)this;
    }

    public <TElementType extends WebComponent, TWaitStrategy extends WaitStrategy> TElementType to(Class<TWaitStrategy> waitClass, TElementType element) {
        var waitStrategy = InstanceFactory.create(waitClass);
        element.ensureState(waitStrategy);
        return element;
    }

    public <TComponent extends WebComponent, TFindStrategy extends FindStrategy> TComponent create(Class<TFindStrategy> findStrategyClass, Class<TComponent> componentClass, Object... args) {
        var findStrategy = InstanceFactory.create(findStrategyClass, args);
        return create(componentClass, findStrategy);
    }

    public <TComponent extends WebComponent, TFindStrategy extends FindStrategy> List<TComponent> createAll(Class<TFindStrategy> findStrategyClass, Class<TComponent> componentClass, Object... args) {
        var findStrategy = InstanceFactory.create(findStrategyClass, args);
        return createAll(componentClass, findStrategy);
    }

    public <TComponent extends WebComponent> TComponent createById(Class<TComponent> componentClass, String id) {
        return create(componentClass, new IdFindStrategy(id));
    }

    public <TComponent extends WebComponent> TComponent createByCss(Class<TComponent> componentClass, String css) {
        return create(componentClass, new CssFindStrategy(css));
    }

    public <TComponent extends WebComponent> TComponent createByClass(Class<TComponent> componentClass, String cclass) {
        return create(componentClass, new ClassFindStrategy(cclass));
    }

    public <TComponent extends WebComponent> TComponent createByXPath(Class<TComponent> componentClass, String xpath) {
        return create(componentClass, new XPathFindStrategy(xpath));
    }

    public <TComponent extends WebComponent> TComponent createByLinkText(Class<TComponent> componentClass, String linkText) {
        return create(componentClass, new LinkTextFindStrategy(linkText));
    }

    public <TComponent extends WebComponent> TComponent createByTag(Class<TComponent> componentClass, String tag) {
        return create(componentClass, new TagFindStrategy(tag));
    }

    public <TComponent extends WebComponent> TComponent createByIdContaining(Class<TComponent> componentClass, String idContaining) {
        return create(componentClass, new IdContainingFindStrategy(idContaining));
    }

    public <TComponent extends WebComponent> TComponent createByInnerTextContaining(Class<TComponent> componentClass, String innerText) {
        return create(componentClass, new InnerTextContainsFindStrategy(innerText));
    }

    public <TComponent extends WebComponent> List<TComponent> createAllById(Class<TComponent> componentClass, String id) {
        return createAll(componentClass, new IdFindStrategy(id));
    }

    public <TComponent extends WebComponent> List<TComponent> createAllByCss(Class<TComponent> componentClass, String css) {
        return createAll(componentClass, new CssFindStrategy(css));
    }

    public <TComponent extends WebComponent> List<TComponent> createAllByClass(Class<TComponent> componentClass, String cclass) {
        return createAll(componentClass, new ClassFindStrategy(cclass));
    }

    public <TComponent extends WebComponent> List<TComponent> createAllByXPath(Class<TComponent> componentClass, String xpath) {
        return createAll(componentClass, new XPathFindStrategy(xpath));
    }

    public <TComponent extends WebComponent> List<TComponent> createAllByLinkText(Class<TComponent> componentClass, String linkText) {
        return createAll(componentClass, new LinkTextFindStrategy(linkText));
    }

    public <TComponent extends WebComponent> List<TComponent> createAllByTag(Class<TComponent> componentClass, String tag) {
        return createAll(componentClass, new TagFindStrategy(tag));
    }

    public <TComponent extends WebComponent> List<TComponent> createAllByIdContaining(Class<TComponent> componentClass, String idContaining) {
        return createAll(componentClass, new IdContainingFindStrategy(idContaining));
    }

    public <TComponent extends WebComponent> List<TComponent> createAllByInnerTextContaining(Class<TComponent> componentClass, String innerText) {
        return createAll(componentClass, new InnerTextContainsFindStrategy(innerText));
    }

    protected <TComponent extends WebComponent, TFindStrategy extends FindStrategy> TComponent create(Class<TComponent> componentClass, TFindStrategy findStrategy) {
        findElement();
        var component = InstanceFactory.create(componentClass);
        component.setFindStrategy(findStrategy);
        component.setParentWrappedElement(wrappedElement);
        return component;
    }

    protected <TComponent extends WebComponent, TFindStrategy extends FindStrategy> List<TComponent> createAll(Class<TComponent> componentClass, TFindStrategy findStrategy) {
        findElement();
        var nativeElements = wrappedElement.findElements(findStrategy.convert());
        List<TComponent> componentList = new ArrayList<>();
        for (int i = 0; i < nativeElements.stream().count(); i++) {
            var component = InstanceFactory.create(componentClass);
            component.setFindStrategy(findStrategy);
            component.setElementIndex(i);
            component.setParentWrappedElement(wrappedElement);
            componentList.add(component);
        }

        return componentList;
    }

    protected WebElement findElement() {
      if (waitStrategies.stream().count() == 0) {
          waitStrategies.add(Wait.to().exists());
      }

      try {
          for (var waitStrategy:waitStrategies) {
              componentWaitService.wait(this, waitStrategy);
          }

          wrappedElement = findNativeElement();
          scrollToMakeElementVisible(wrappedElement);
          if (webSettings.getWaitUntilReadyOnElementFound()) {
              browserService.waitForAjax();
          }

          if (webSettings.getWaitForAngular()) {
              browserService.waitForAngular();
          }

          addArtificialDelay();

          waitStrategies.clear();
      } catch (WebDriverException ex) {
          System.out.print(String.format("\n\nThe element: \n Name: '%s', \n Locator: '%s = %s', \nWas not found on the page or didn't fulfill the specified conditions.\n\n", getComponentClass().getSimpleName(), findStrategy.toString(), findStrategy.getValue()));
      }

        return wrappedElement;
    }


    protected void click(EventListener<ComponentActionEventArgs> clicking, EventListener<ComponentActionEventArgs> clicked)
    {
        clicking.broadcast(new ComponentActionEventArgs(this));

        this.toExists().toBeClickable().waitToBe();
        javaScriptService.execute("arguments[0].focus();arguments[0].click();", wrappedElement);

        clicked.broadcast(new ComponentActionEventArgs(this));
    }
//
//    internal void Hover(EventHandler<ElementActionEventArgs> hovering, EventHandler<ElementActionEventArgs> hovered)
//    {
//        hovering?.Invoke(this, new ElementActionEventArgs(this));
//
//        JavaScriptService.Execute("arguments[0].onmouseover();", this);
//
//        hovered?.Invoke(this, new ElementActionEventArgs(this));
//    }
//
//    internal string GetInnerText()
//    {
//        return WrappedElement.Text.Replace("\r\n", string.Empty);
//    }
//
//    internal void SetValue(EventHandler<ElementActionEventArgs> gettingValue, EventHandler<ElementActionEventArgs> gotValue, string value)
//    {
//        gettingValue?.Invoke(this, new ElementActionEventArgs(this, value));
//        SetAttribute("value", value);
//        gotValue?.Invoke(this, new ElementActionEventArgs(this, value));
//    }
//
//    internal string DefaultGetValue()
//    {
//        return WrappedElement.GetAttribute("value");
//    }
//
//    internal int? DefaultGetMaxLength()
//    {
//        int? result = string.IsNullOrEmpty(GetAttribute("maxlength")) ? null : (int?)int.Parse(GetAttribute("maxlength"));
//        if (result != null && (result == 2147483647 || result == -1))
//        {
//            result = null;
//        }
//
//        return result;
//    }
//
//    internal int? DefaultGetMinLength()
//    {
//        int? result = string.IsNullOrEmpty(GetAttribute("minlength")) ? null : (int?)int.Parse(GetAttribute("minlength"));
//
//        if (result != null && result == -1)
//        {
//            result = null;
//        }
//
//        return result;
//    }
//
//    internal int? GetSizeAttribute()
//    {
//        return string.IsNullOrEmpty(GetAttribute("size")) ? null : (int?)int.Parse(GetAttribute("size"));
//    }
//
//    internal int? GetHeightAttribute()
//    {
//        return string.IsNullOrEmpty(GetAttribute("height")) ? null : (int?)int.Parse(GetAttribute("height"));
//    }
//
//    internal int? GetWidthAttribute()
//    {
//        return string.IsNullOrEmpty(GetAttribute("width")) ? null : (int?)int.Parse(GetAttribute("width"));
//    }
//
//    internal string GetInnerHtmlAttribute()
//    {
//        return WrappedElement.GetAttribute("innerHTML");
//    }
//
//    internal string GetForAttribute()
//    {
//        return string.IsNullOrEmpty(GetAttribute("for")) ? null : GetAttribute("for");
//    }
//
//    protected bool GetDisabledAttribute()
//    {
//        string valueAttr = WrappedElement.GetAttribute("disabled");
//        return valueAttr == "true";
//    }
//
//    internal string GetText()
//    {
//        return WrappedElement.Text;
//    }
//
//    internal int? GetMinAttribute()
//    {
//        return string.IsNullOrEmpty(GetAttribute("min")) ? null : (int?)int.Parse(GetAttribute("min"));
//    }
//
//    internal int? GetMaxAttribute()
//    {
//        return string.IsNullOrEmpty(GetAttribute("max")) ? null : (int?)int.Parse(GetAttribute("max"));
//    }
//
//    internal string GetMinAttributeAsString()
//    {
//        return string.IsNullOrEmpty(GetAttribute("min")) ? null : GetAttribute("min");
//    }
//
//    internal string GetMaxAttributeAsString()
//    {
//        return string.IsNullOrEmpty(GetAttribute("max")) ? null : GetAttribute("max");
//    }
//
//    internal int? GetStepAttribute()
//    {
//        return string.IsNullOrEmpty(GetAttribute("step")) ? null : (int?)int.Parse(GetAttribute("step"));
//    }
//
//    internal string GetPlaceholderAttribute()
//    {
//        return string.IsNullOrEmpty(GetAttribute("placeholder")) ? null : GetAttribute("placeholder");
//    }
//
//    internal bool GetAutoCompleteAttribute()
//    {
//        return GetAttribute("autocomplete") == "on";
//    }
//
//    internal bool GetReadonlyAttribute()
//    {
//        return !string.IsNullOrEmpty(GetAttribute("readonly"));
//    }
//
//    internal bool GetRequiredAttribute()
//    {
//        return !string.IsNullOrEmpty(GetAttribute("required"));
//    }
//
//    internal string GetList()
//    {
//        return string.IsNullOrEmpty(GetAttribute("list")) ? null : GetAttribute("list");
//    }
//
//    internal void DefaultSetText(EventHandler<ElementActionEventArgs> settingValue, EventHandler<ElementActionEventArgs> valueSet, string value)
//    {
//        settingValue?.Invoke(this, new ElementActionEventArgs(this, value));
//
//        findElement().clear();
//        findElement().sendKeys(value);
//
//        valueSet?.Invoke(this, new ElementActionEventArgs(this, value));
//    }

    private WebElement findNativeElement() {
        if (parentWrappedElement == null) {
            return wrappedDriver.findElements(findStrategy.convert()).get(elementIndex);
        } else {
            return parentWrappedElement.findElements(findStrategy.convert()).get(elementIndex);
        }
    }

    private void addArtificialDelay() {
        if (webSettings.getArtificialDelayBeforeAction() != 0)
        {
            try {
                Thread.sleep(webSettings.getArtificialDelayBeforeAction());
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
    }

    private void scrollToMakeElementVisible(WebElement wrappedElement) {
        // createBy default scroll down to make the element visible.
        if (webSettings.getAutomaticallyScrollToVisible()) {
            scrollToVisible(wrappedElement, false);
        }
    }

    private void scrollToVisible(WebElement wrappedElement, Boolean shouldWait)
    {
        //ScrollingToVisible?.Invoke(this, new ElementActionEventArgs(this));
        try {
            javaScriptService.execute("arguments[0].scrollIntoView(true);", wrappedElement);
            if (shouldWait)
            {
                Thread.sleep(500);
                toExists().waitToBe();
            }
        } catch (ElementNotInteractableException ex) {
            System.out.print(ex);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }

        //ScrolledToVisible?.Invoke(this, new ElementActionEventArgs(this));
    }
}
