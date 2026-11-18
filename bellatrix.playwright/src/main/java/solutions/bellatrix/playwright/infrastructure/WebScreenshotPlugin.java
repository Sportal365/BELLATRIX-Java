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

package solutions.bellatrix.playwright.infrastructure;

import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.ScreenshotType;
import lombok.SneakyThrows;
import plugins.screenshots.ScreenshotPlugin;
import plugins.screenshots.ScreenshotPluginEventArgs;
import solutions.bellatrix.core.plugins.TestResult;
import solutions.bellatrix.core.utilities.PathNormalizer;
import solutions.bellatrix.playwright.utilities.Settings;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.UUID;

public class WebScreenshotPlugin extends ScreenshotPlugin {
    public WebScreenshotPlugin() {
        super(Boolean.TRUE.equals(Settings.web().getScreenshotsOnFailEnabled()));
    }

    @Override
    public void postAfterTest(TestResult testResult, Method memberInfo, Throwable failedTestException) {
        if (testResult == TestResult.FAILURE) {
            captureFailure(memberInfo);
        }
    }

    static void captureFailure(Method memberInfo) {
        if (!Boolean.TRUE.equals(Settings.web().getScreenshotsOnFailEnabled())) {
            return;
        }
        WrappedBrowser browser = PlaywrightService.wrappedBrowser();
        if (browser != null && browser.getCurrentPage() != null && !browser.getCurrentPage().isClosed()) {
            String name = memberInfo.getDeclaringClass().getSimpleName() + "-" + memberInfo.getName();
            capture(outputFolder(), uniqueFileName(name));
        }
    }

    static Path failureTracePath(Method memberInfo) {
        String name = memberInfo.getDeclaringClass().getSimpleName() + "-" + memberInfo.getName();
        return Path.of(outputFolder()).toAbsolutePath().normalize().resolve(name + "-" + UUID.randomUUID() + ".trace.zip");
    }

    @Override
    public byte[] takeScreenshot() {
        return PlaywrightService.wrappedBrowser().getCurrentPage()
                .screenshot(new Page.ScreenshotOptions()
                        .setType(ScreenshotType.PNG)
                        .setFullPage(false));
    }

    @Override
    public String takeScreenshot(String name) {
        return takeScreenshot(getOutputFolder(), getUniqueFileName(name));
    }

    @Override
    public String takeScreenshot(String screenshotSaveDir, String filename) {
        return capture(screenshotSaveDir, filename);
    }

    @SneakyThrows
    private static String capture(String screenshotSaveDir, String filename) {
        var directory = Path.of(screenshotSaveDir).toAbsolutePath().normalize();
        Files.createDirectories(directory);
        var path = directory.resolve(filename);
        var screenshot = PlaywrightService.wrappedBrowser().getCurrentPage()
                .screenshot(new Page.ScreenshotOptions()
                        .setPath(path)
                        .setFullPage(true)
                        .setTimeout(5_000)
                        .setType(ScreenshotType.PNG));

        var image = Base64.getEncoder().encodeToString(screenshot);

        SCREENSHOT_GENERATED.broadcast(new ScreenshotPluginEventArgs(path.toString(), filename, image));
        return image;
    }

    @Override
    protected String getOutputFolder() {
        return outputFolder();
    }

    @Override
    protected String getUniqueFileName(String testName) {
        return uniqueFileName(testName);
    }

    private static String outputFolder() {
        String saveLocation = Settings.web().getScreenshotsSaveLocation();
        return PathNormalizer.normalizePath(saveLocation);
    }

    private static String uniqueFileName(String testName) {
        return testName + "-" + UUID.randomUUID() + ".png";
    }
}
