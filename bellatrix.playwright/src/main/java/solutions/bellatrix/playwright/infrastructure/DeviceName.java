package solutions.bellatrix.playwright.infrastructure;

import lombok.Getter;

@Getter
public enum DeviceName {
    NOT_SET(0, 0, 1),
    MOBILE_S(300, 873, 1),
    MOBILE_M(320, 873, 1),
    MOBILE_L(393, 873, 1),
    TABLET_SMALL(651, 873, 1),
    TABLET(771, 873, 1),
    SURFACE_PRO_7_L("Surface Pro 7 L", 915, 844, 1),
    SURFACE_PRO_7_M("Surface Pro 7 M", 445, 844, 1),
    IPHONE_13_PRO_MOBILE("iPhone 13 Pro", 375, 667, 2),
    IPHONE_12_PRO_MOBILE("iPhone 12 Pro", 390, 844, 2),
    IPHONE_SE_MOBILE("iPhone SE", 375, 667, 2),
    IPHONE_X_MOBILE("iPhone X", 375, 667, 2),
    NEXUS_7_TABLET("Nexus 7", 600, 960, 2),
    IPAD("Apple iPad", 768, 1024, 2);

    static final String CHROME_MOBILE_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 4.2.1; en-us; Nexus 5 Build/JOP40D) AppleWebKit/535.19 "
                    + "(KHTML, like Gecko) Chrome/18.0.1025.166 Mobile Safari/535.19";

    private final int width;
    private final int height;
    private final int scaleFactor;
    private final String displayName;

    DeviceName(int width, int height, int scaleFactor) {
        this(null, width, height, scaleFactor);
    }

    DeviceName(String displayName, int width, int height, int scaleFactor) {
        this.displayName = displayName;
        this.width = width;
        this.height = height;
        this.scaleFactor = scaleFactor;
    }

    public String getName() {
        if (this == NOT_SET) {
            return null;
        }
        return displayName == null ? name() : displayName;
    }
}
