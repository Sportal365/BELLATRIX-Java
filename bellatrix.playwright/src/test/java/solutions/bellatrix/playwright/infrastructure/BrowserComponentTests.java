package solutions.bellatrix.playwright.infrastructure;

import com.microsoft.playwright.Route;
import com.microsoft.playwright.TimeoutError;
import com.microsoft.playwright.options.AriaRole;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.opentest4j.AssertionFailedError;
import solutions.bellatrix.playwright.components.Anchor;
import solutions.bellatrix.playwright.components.Button;
import solutions.bellatrix.playwright.components.CheckBox;
import solutions.bellatrix.playwright.components.Div;
import solutions.bellatrix.playwright.components.FileInput;
import solutions.bellatrix.playwright.components.Frame;
import solutions.bellatrix.playwright.components.Image;
import solutions.bellatrix.playwright.components.PasswordInput;
import solutions.bellatrix.playwright.components.Span;
import solutions.bellatrix.playwright.components.TextArea;
import solutions.bellatrix.playwright.components.TextInput;
import solutions.bellatrix.playwright.components.common.ComponentActionEventArgs;
import solutions.bellatrix.playwright.components.enums.CssStyle;
import solutions.bellatrix.playwright.configuration.WebSettings;
import solutions.bellatrix.playwright.findstrategies.LabelFindStrategy;
import solutions.bellatrix.playwright.infrastructure.junit.WebTest;
import solutions.bellatrix.playwright.utilities.Settings;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.logging.Level;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;

@Tag("browser-contract")
class BrowserComponentTests extends WebTest {
    @Test
    void letsNumericInputsRejectNonNumericKeystrokes() {
        app().browser().page().setContent("<input id='number' type='number' value='12'>");
        var input = app().create().byId(TextInput.class, "number");
        input.setText("TEST");
        assertEquals("", input.getValue());
        input.setText("42");
        assertEquals("42", input.getValue());
    }

    @Test
    void readsApplicationClipboardWritesWithoutAnEarlierFrameworkWrite() {
        var page = app().browser().page();
        page.route("https://clipboard.fixture/**", route -> route.fulfill(new Route.FulfillOptions()
                .setContentType("text/html").setBody("""
                        <button onclick="navigator.clipboard.writeText('Application copy').then(()=>this.textContent='Copied')">Copy</button>
                        """)));
        page.navigate("https://clipboard.fixture/");
        app().browser().runAndWaitForClipboardCopy(() ->
                page.getByText("Copy", new com.microsoft.playwright.Page.GetByTextOptions().setExact(true)).click());
        assertThat(page.getByRole(AriaRole.BUTTON)).hasText("Copied");
        assertEquals("Application copy", app().browser().getLastClipboardEntry());
    }

    @Test
    void fillsSensitiveInputsWithoutExposingValuesInEventsOrFailures() {
        var page = app().browser().page();
        page.setContent("""
                <input id="account">
                <input id="secret" type="password">
                <input id="unavailable" type="password" disabled>
                """);
        var account = app().create().byId(TextInput.class, "account");
        var password = app().create().byId(PasswordInput.class, "secret");
        var unavailable = app().create().byId(PasswordInput.class, "unavailable");
        var captured = new ArrayList<String>();
        Consumer<ComponentActionEventArgs> capture = event -> captured.add(event.getActionValue());
        var inputEvents = List.of(TextInput.SETTING_TEXT, TextInput.TEXT_SET,
                PasswordInput.SETTING_PASSWORD, PasswordInput.PASSWORD_SET);
        inputEvents.forEach(event -> event.addListener(capture));
        var fixtureValue = "fictional fixture input 'quoted'";
        try {
            account.setSensitiveText(fixtureValue);
            password.setPassword(fixtureValue);
            assertTrue(fixtureValue.equals(account.getValue()));
            assertTrue(fixtureValue.equals(password.getPassword()));
            assertEquals(List.of("*****", "*****", "*****", "*****"), captured);

            page.setDefaultTimeout(350);
            var failure = assertThrows(TimeoutError.class, () -> unavailable.setPassword(fixtureValue));
            assertFalse(failure.getMessage().contains(fixtureValue));
            assertTrue(failure.getMessage().contains("*****"));
            assertTrue(failure.getMessage().contains("enabled"));
            assertNull(failure.getCause());
            assertEquals(List.of("*****", "*****", "*****", "*****", "*****"), captured);
        } finally {
            inputEvents.forEach(event -> event.removeListener(capture));
        }
    }

    @Test
    void readsLiveFieldValuesAndRenderedTextFromMappedComponents() {
        var page = app().browser().page();
        page.setContent("""
                <input id="title" value="Initial title">
                <textarea id="details">Initial details</textarea>
                <div id="editor" contenteditable="true">Initial content</div>
                <a id="search-result">Matching article</a>
                <p id="sidebar-status">Settings changed</p>
                """);
        page.locator("#title").fill("Updated title");
        page.locator("#details").fill("Updated details");
        page.locator("#editor").fill("Updated content");

        var factory = app().create();
        var title = factory.byId(TextInput.class, "title");
        var details = factory.byId(TextArea.class, "details");
        assertEquals("Updated title", title.getText());
        assertEquals("Updated title", title.getValue());
        assertEquals("Updated details", details.getText());
        assertEquals("Updated details", details.getValue());
        assertEquals("Updated content", factory.byId(TextInput.class, "editor").getText());
        assertEquals("Matching article", factory.byId(TextArea.class, "search-result").getText());
        assertEquals("Settings changed", factory.byId(TextArea.class, "sidebar-status").getText());
        page.locator("#details").clear();
        assertEquals("", details.getText());
    }

    @Test
    void matchesASingleClassWithinMultipleClasses() {
        app().browser().page().setContent("""
                <div class="not-breadcrumb">Decoy</div>
                <div class="panel breadcrumb selected" id="target">Breadcrumb</div>
                """);
        var match = app().create().byClass(Div.class, "breadcrumb");
        assertEquals("target", match.getAttribute("id"));
        assertEquals("panel breadcrumb selected", match.getHtmlClass());
        assertEquals(1, app().create().allByClass(Div.class, "breadcrumb").size());
    }

    @Test
    void setsCheckboxStateAndClicksImageThroughNativeActions() {
        var page = app().browser().page();
        page.setContent("""
                <input id="feature" type="checkbox" onchange="document.body.dataset.checked = this.checked">
                <img id="preview" title="Preview" width="24" height="24" onclick="document.body.dataset.preview = 'clicked'">
                """);
        var checkBox = app().create().byId(CheckBox.class, "feature");
        assertSame(page, checkBox.getBrowserService().page());
        checkBox.check(true);
        assertTrue(checkBox.isChecked());
        checkBox.validateIsChecked(true);
        assertEquals("true", page.locator("body").getAttribute("data-checked"));
        checkBox.check(false);
        assertFalse(checkBox.isChecked());
        checkBox.validateIsChecked(false);
        assertEquals("false", page.locator("body").getAttribute("data-checked"));
        var image = app().create().byId(Image.class, "preview");
        assertEquals("Preview", image.getTitle());
        image.click();
        assertEquals("clicked", page.locator("body").getAttribute("data-preview"));
    }

    @Test
    void reusesLocatorAfterReplacementAndChecksActionability() {
        var page = app().browser().page();
        page.setContent("""
                <button class="action">Outside</button>
                <section id="form">
                  <button class="action" style="color: red" onclick="window.clicked = 'first'">First</button>
                  <button id="disabled" disabled onclick="window.clicked = 'disabled'">Disabled</button>
                  <textarea id="note">Initial</textarea>
                </section>
                """);
        var form = app().create().byId(Div.class, "form");
        var action = form.createByXPath(Button.class, "(//button[@class='action'])[1]");
        var note = form.createById(TextArea.class, "note");
        assertEquals("rgba(255, 0, 0, 1)", action.getCssValue(CssStyle.COLOR));
        action.click();
        assertEquals("first", page.evaluate("window.clicked"));
        page.locator("#form .action").evaluate("""
                old => {
                  const replacement = document.createElement('button');
                  replacement.className = 'action';
                  replacement.textContent = 'Second';
                  replacement.onclick = () => window.clicked = 'second';
                  old.replaceWith(replacement);
                }
                """);
        action.click();
        assertEquals("Second", action.getText());
        assertEquals("second", page.evaluate("window.clicked"));
        var disabled = form.createById(Button.class, "disabled");
        assertThrows(TimeoutError.class, disabled::click);
        assertEquals("second", page.evaluate("window.clicked"));
        note.setText("Updated");
        assertEquals("Updated", note.getText());
    }

    @Test
    void scopesFrameAndShadowLocatorsToTheirOwners() {
        var page = app().browser().page();
        page.setContent("""
                <span data-testid="scope">Main page</span>
                <iframe id="preview" srcdoc="<span data-testid='scope'>Frame</span><div id='host'>
                  <button class='action'>Light DOM</button></div>"></iframe>
                """);
        var frame = app().create().byId(Frame.class, "preview");
        var factory = app().create().fromFrame(frame.frameLocator());
        assertEquals("Frame", factory.byTestId(Span.class, "scope").getText());
        assertEquals("Main page", app().create().fromLocator(Span.class, page.getByTestId("scope")).getText());
        var host = frame.createById(Div.class, "host");
        host.evaluate("""
                host => {
                  const root = host.attachShadow({mode:'open'});
                  root.innerHTML = '<button>Decoy role</button><button class="action">Shadow</button><div id="nested"></div>';
                  root.querySelector('#nested').attachShadow({mode:'open'}).innerHTML = '<span>Nested</span>';
                }
                """);
        host.toShadowRootToBeAttached();
        var shadow = host.getShadowRoot();
        var byCss = shadow.createByCss(Button.class, ".action");
        var byXpath = shadow.createByXPath(Button.class, "//button[@class='action']");
        assertEquals("Shadow", byCss.getText());
        assertEquals("Shadow", byXpath.getText());
        assertEquals("Shadow", shadow.create().byRole(Button.class, AriaRole.BUTTON,
                options -> options.setName("Shadow").setExact(true)).getText());
        assertEquals(1, shadow.createAllByCss(Button.class, ".action").size());
        assertEquals("Nested", shadow.createByTag(Span.class, "span").getText());
        host.evaluate("""
                host => host.shadowRoot.querySelector('.action').outerHTML =
                    '<button class="action">Replacement</button>'
                """);
        assertEquals("Replacement", byCss.getText());
        assertEquals("Replacement", byXpath.getText());
    }

    @Test
    void readsBrowserResolvedRelativeLinksAndImages() {
        app().browser().context().route("https://fixture.test/**",
                route -> route.fulfill(new Route.FulfillOptions().setStatus(204)));
        app().browser().page().setContent("""
                <base href="https://fixture.test/widgets/">
                <a id="relative" href="article%20name#details">Article</a>
                <a id="empty">Empty link</a>
                <img id="image" src="icons/team.svg" loading="lazy">
                <img id="no-image">
                """);
        assertEquals("https://fixture.test/widgets/article name#details",
                app().create().byId(Anchor.class, "relative").getHref());
        assertEquals("", app().create().byId(Anchor.class, "empty").getHref());
        assertEquals("https://fixture.test/widgets/icons/team.svg", app().create().byId(Image.class, "image").getSrc());
        assertEquals("", app().create().byId(Image.class, "no-image").getSrc());
    }

    @Test
    void locatesTextAttributesWithDefaultAndExplicitOptions() {
        app().browser().page().setContent("""
                <input id="main" placeholder="Filter options">
                <input id="exact" placeholder="Filter">
                <label for="labelled">Filter label</label><input id="labelled">
                <iframe id="preview" srcdoc="<button id='long-title' title='Widget settings'>Settings</button>
                  <button id='short-title' title='Widget'>Widget</button>
                  <img id='logo' alt='Club logo'>"></iframe>
                """);
        var factory = app().create();
        assertEquals("main", factory.byPlaceholder(TextInput.class, "Filter").getAttribute("id"));
        assertEquals("exact", factory.byPlaceholder(TextInput.class, "Filter",
                ignored -> new com.microsoft.playwright.Page.GetByPlaceholderOptions().setExact(true)).getAttribute("id"));
        assertEquals("labelled", factory.by(TextInput.class, new LabelFindStrategy("Filter label")).getAttribute("id"));
        var frame = factory.byId(Frame.class, "preview");
        assertEquals("long-title", frame.create().byTitle(Button.class, "Widget").getAttribute("id"));
        assertEquals("short-title", frame.create().byTitle(Button.class, "Widget",
                options -> options.setExact(true)).getAttribute("id"));
        assertEquals("logo", frame.create().byAltText(Image.class, "Club logo").getAttribute("id"));
    }

    @Test
    void evaluatesScriptsOnMappedComponentsAndSurfacesScriptErrors() {
        var page = app().browser().page();
        page.setContent("<p id='left'>Left</p><p id='right'>Right</p>");
        var left = app().create().byId(Div.class, "left");
        assertEquals("Left", app().script().execute("element => element.textContent", left));
        assertThrows(com.microsoft.playwright.PlaywrightException.class,
                () -> app().script().execute("() => { throw new Error('fixture error') }"));
    }

    @Test
    void consoleErrorIsIgnored_when_failedResourceUrlMatchesWhitelistedUrlFragment() {
        withConsoleWhitelist(List.of("console.fixture/role"), () -> {
            loadFailingResource("role", 404);
            assertEquals(1, app().browser().consoleErrors().size());
            assertEquals(List.of(), app().browser().getSevereLogEntries());
            app().browser().assertNoConsoleErrorsLogged();
        });
    }

    @Test
    void consoleErrorIsIgnored_when_failedResourceMatchesWhitelistedStatusLine() {
        withConsoleWhitelist(List.of(" - Failed to load resource: the server responded with a status of 400"), () -> {
            loadFailingResource("invalid", 400);
            assertEquals(1, app().browser().consoleErrors().size());
            assertEquals(List.of(), app().browser().getSevereLogEntries());
            app().browser().assertNoConsoleErrorsLogged();
        });
    }

    @Test
    void consoleErrorFailsWithItsResourceLocation_when_failedResourceMatchesNoWhitelistEntry() {
        withConsoleWhitelist(List.of("console.fixture/role",
                " - Failed to load resource: the server responded with a status of 400"), () -> {
            loadFailingResource("unlisted", 404);
            List<String> severe = app().browser().getSevereLogEntries();
            assertEquals(1, severe.size());
            assertTrue(severe.get(0).startsWith("Failed to load resource: the server responded with a status of 404"),
                    severe::toString);
            AssertionError failure = assertThrows(AssertionError.class, () -> app().browser().assertNoConsoleErrorsLogged());
            assertTrue(failure.getMessage().startsWith("Severe errors found in browser console "
                    + "(resource locations: [https://console.fixture/unlisted])"), failure::getMessage);
        });
    }

    @Test
    void consoleReadIncludesErrorLogged_when_noBrowserCallRanSinceTheErrorWasLogged() throws Exception {
        var logged = new CountDownLatch(1);
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            if (exchange.getRequestURI().getPath().equals("/logged")) {
                logged.countDown();
            }
            try (exchange) {
                byte[] body = "<!doctype html><title>Console</title>".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            }
        });
        server.start();
        try {
            app().browser().page().navigate("http://127.0.0.1:" + server.getAddress().getPort() + "/");
            app().browser().page().evaluate("() => setTimeout(() => { console.error('late error');"
                    + " setTimeout(() => fetch('/logged'), 100); }, 100)");
            assertTrue(logged.await(10, TimeUnit.SECONDS), "The fixture page did not log its error");

            assertEquals(List.of(), PlaywrightService.wrappedBrowser().getConsoleErrors());
            assertEquals(List.of("late error"), app().browser().consoleErrors());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void expectedConsoleErrorIsLeftOutOfTheChecks_when_itArrivesAfterTheDeclaration() {
        app().browser().expectConsoleErrors(entry -> entry.location() != null
                && entry.location().startsWith("https://console.fixture/expected"));
        app().browser().context().route("https://console.fixture/**",
                route -> route.fulfill(new Route.FulfillOptions().setStatus(404)));
        app().browser().page().setContent("<img src='https://console.fixture/expected'><img src='https://console.fixture/unexpected'>");
        app().browser().waitUntil(() -> app().browser().consoleEntries().size() == 2);

        assertEquals(1, app().browser().consoleErrors().size());
        assertEquals(1, app().browser().getSevereLogEntries().size());
        AssertionError failure = assertThrows(AssertionError.class, () -> app().browser().assertNoConsoleErrorsLogged());
        assertTrue(failure.getMessage().startsWith("Severe errors found in browser console "
                + "(resource locations: [https://console.fixture/unexpected])"), failure::getMessage);
    }

    @Test
    void consoleMessageAssertionWaitsForTheMessage_when_pageLogsItLate() {
        app().browser().page().setContent("<script>setTimeout(() => console.warn('late warning'), 300)</script>");

        app().browser().assertConsoleErrorLogged("late warning", Level.WARNING);

        AssertionFailedError failure = assertThrows(AssertionFailedError.class,
                () -> app().browser().assertConsoleErrorLogged("absent warning", Level.WARNING));
        assertTrue(failure.getMessage().startsWith("Expected console warning containing absent warning within"),
                failure::getMessage);
    }

    @Test
    void popupIsClosed_when_itIsStillLoading() {
        var page = app().browser().page();
        var heldScript = new AtomicReference<Route>();
        app().browser().context().route("https://popup.fixture/article", route -> route.fulfill(new Route.FulfillOptions()
                .setContentType("text/html").setBody("<script src='/held.js'></script><p>Article</p>")));
        app().browser().context().route("https://popup.fixture/held.js", heldScript::set);
        page.setContent("<button onclick=\"window.open('https://popup.fixture/article')\">Open</button>");
        var popup = app().browser().runAndWaitForPopup(() -> page.getByText("Open").click());
        app().browser().waitForCondition(() -> heldScript.get() != null);

        app().browser().closePage(popup);

        assertTrue(popup.isClosed());
        assertEquals(List.of(), app().browser().consoleErrors());
    }


    @Test
    void textValidationReportsTheTextSeenAtFailure_when_textChangesDuringTheWait() {
        app().browser().page().setContent("<div id='status'>Loading</div><script>"
                + "setTimeout(() => document.getElementById('status').textContent = 'Still loading', 300)</script>");
        Div status = app().create().byId(Div.class, "status");

        AssertionFailedError failure = assertThrows(AssertionFailedError.class, () -> status.validateTextIs("Ready"));

        assertTrue(failure.getMessage().contains("But was: \"Still loading\""), failure::getMessage);
    }

    private void loadFailingResource(String path, int status) {
        app().browser().context().route("https://console.fixture/**",
                route -> route.fulfill(new Route.FulfillOptions().setStatus(status)));
        app().browser().page().setContent("<img src='https://console.fixture/" + path + "'>");
        app().browser().waitUntil(() -> !app().browser().consoleErrors().isEmpty());
    }

    private static void withConsoleWhitelist(List<String> whitelist, Runnable check) {
        WebSettings settings = Settings.web();
        List<String> original = settings.getConsoleErrorsWhitelist();
        settings.setConsoleErrorsWhitelist(whitelist);
        try {
            check.run();
        } finally {
            settings.setConsoleErrorsWhitelist(original);
        }
    }

    @Test
    void pastesRealClipboardContentAndUploadsRunnerFile(@TempDir Path directory) throws Exception {
        var html = directory.resolve("browser-inputs.html");
        Files.writeString(html, """
                <textarea id="paste"></textarea>
                <button id="copy" onclick="navigator.clipboard.writeText('Browser copy').then(()=>this.textContent='Copied')">Copy</button>
                <input type="file" id="upload" onchange="this.files[0].text().then(value=>document.getElementById('uploaded').textContent=value)">
                <pre id="uploaded"></pre>
                """);
        var payload = directory.resolve("payload.txt");
        var content = "Runner file: Български, quotes '\" and newline\nsecond line";
        Files.writeString(payload, content);
        app().navigate().to(html.toUri().toString());
        var page = app().browser().page();
        app().browser().copyTextToClipboard(content);
        var paste = page.locator("#paste");
        paste.press("ControlOrMeta+v");
        assertThat(paste).hasValue(content);
        page.locator("#copy").click();
        assertThat(page.locator("#copy")).hasText("Copied");
        assertEquals("Browser copy", app().browser().getLastClipboardEntry());
        app().browser().copyTextToClipboard("");
        assertEquals("", app().browser().getLastClipboardEntry());
        app().create().byId(FileInput.class, "upload").upload(payload);
        page.waitForFunction("text => document.querySelector('#uploaded').textContent === text", content);
        assertEquals(content, page.locator("#uploaded").textContent());
        assertEquals(payload.getFileName().toString(), page.locator("#upload").evaluate("input => input.files[0].name"));
    }
}
