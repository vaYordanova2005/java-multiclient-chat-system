import javafx.scene.Node;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.shape.*;
import java.util.List;

/**
 * Каталог от 5 кръгли аватари — построени изцяло от
 * JavaFX shape примитиви (Circle, Arc, Ellipse)
*/
public class AvatarCatalog {

    public static class AvatarDef {
        public final String id;
        public final String displayName;
        public final String bgColor;

        public AvatarDef(String id, String displayName, String bgColor) {
            this.id = id;
            this.displayName = displayName;
            this.bgColor = bgColor;
        }
    }

    public static final AvatarDef AVATAR_1 = new AvatarDef("avatar_sun", "Sunny", "#FCDCE1");
    public static final AvatarDef AVATAR_2 = new AvatarDef("avatar_cat", "Kitty", "#C8CEEE");
    public static final AvatarDef AVATAR_3 = new AvatarDef("avatar_star", "Star", "#D8BEE5");
    public static final AvatarDef AVATAR_4 = new AvatarDef("avatar_cloud", "Cloudy", "#E8DAF0");
    public static final AvatarDef AVATAR_5 = new AvatarDef("avatar_fox", "Foxy", "#A7ABDE");

    public static final List<AvatarDef> ALL_AVATARS = List.of(
            AVATAR_1, AVATAR_2, AVATAR_3, AVATAR_4, AVATAR_5
    );

    public static AvatarDef getById(String id) {
        if (id == null) return null;
        for (AvatarDef a : ALL_AVATARS) {
            if (a.id.equals(id)) return a;
        }
        return null;
    }

    /**
 * Изгражда визуален Node за дадения avatar id, при зададен радиус.
 * Връща null ако id е невалиден/null — извикващият код тогава пада
 * обратно на стандартния инициал-кръг (fallback поведение, запазено).
*/
    public static Node build(String avatarId, double radius) {
        AvatarDef def = getById(avatarId);
        if (def == null) return null;

        Circle bg = new Circle(radius);
        bg.setFill(Color.web(def.bgColor));

        Node face = switch (def.id) {
            case "avatar_sun"   -> buildSunFace(radius);
            case "avatar_cat"   -> buildCatFace(radius);
            case "avatar_star"  -> buildStarFace(radius);
            case "avatar_cloud" -> buildCloudFace(radius);
            case "avatar_fox"   -> buildFoxFace(radius);
            default -> null;
        };

        StackPane pane = face != null ? new StackPane(bg, face) : new StackPane(bg);
        pane.setPrefSize(radius * 2, radius * 2);
        return pane;
    }

    // ── Sunny: кръгло усмихнато лице с лъчи ──────────────────────────
    private static Node buildSunFace(double r) {
        Circle leftEye = dot(-r * 0.28, -r * 0.1, r * 0.09, "#3d3458");
        Circle rightEye = dot(r * 0.28, -r * 0.1, r * 0.09, "#3d3458");
        Arc smile = smileArc(r, "#3d3458");
        javafx.scene.Group g = new javafx.scene.Group(leftEye, rightEye, smile);
        return g;
    }

    // ── Kitty: триъгълни ушички + мустаци ────────────────────────────
    private static Node buildCatFace(double r) {
        Polygon leftEar = new Polygon(
                -r * 0.5, -r * 0.55,
                -r * 0.25, -r * 0.95,
                -r * 0.05, -r * 0.55
        );
        leftEar.setFill(Color.web("#9FA6D6"));

        Polygon rightEar = new Polygon(
                r * 0.5, -r * 0.55,
                r * 0.25, -r * 0.95,
                r * 0.05, -r * 0.55
        );
        rightEar.setFill(Color.web("#9FA6D6"));

        Circle leftEye = dot(-r * 0.25, -r * 0.05, r * 0.08, "#3d3458");
        Circle rightEye = dot(r * 0.25, -r * 0.05, r * 0.08, "#3d3458");
        Polygon nose = new Polygon(
                -r * 0.06, r * 0.15,
                r * 0.06, r * 0.15,
                0, r * 0.25
        );
        nose.setFill(Color.web("#3d3458"));

        return new javafx.scene.Group(leftEar, rightEar, leftEye, rightEye, nose);
    }

    // ── Star: петолъчка в центъра ─────────────────────────────────────
    private static Node buildStarFace(double r) {
        double outer = r * 0.6;
        double inner = r * 0.28;
        double[] pts = new double[20];
        for (int i = 0; i < 10; i++) {
            double angle = Math.PI / 2 + i * Math.PI / 5;
            double radius = (i % 2 == 0) ? outer : inner;
            pts[i * 2] = radius * Math.cos(angle);
            pts[i * 2 + 1] = -radius * Math.sin(angle);
        }
        Polygon star = new Polygon(pts);
        star.setFill(Color.web("#FFFFFF"));
        star.setOpacity(0.9);
        return star;
    }

    // ── Cloudy: три застъпващи се елипси (форма на облак) ───────────
    private static Node buildCloudFace(double r) {
        Ellipse main = new Ellipse(0, r * 0.05, r * 0.55, r * 0.32);
        Ellipse left = new Ellipse(-r * 0.35, -r * 0.05, r * 0.3, r * 0.25);
        Ellipse right = new Ellipse(r * 0.35, -r * 0.05, r * 0.3, r * 0.25);
        for (Ellipse e : List.of(main, left, right)) {
            e.setFill(Color.web("#FFFFFF"));
            e.setOpacity(0.95);
        }
        return new javafx.scene.Group(left, right, main);
    }

    // ── Foxy: триъгълни ушички + остра муцунка ───────────────────────
    private static Node buildFoxFace(double r) {
        Polygon leftEar = new Polygon(
                -r * 0.45, -r * 0.5,
                -r * 0.55, -r * 0.95,
                -r * 0.15, -r * 0.6
        );
        leftEar.setFill(Color.web("#F7C9A0"));

        Polygon rightEar = new Polygon(
                r * 0.45, -r * 0.5,
                r * 0.55, -r * 0.95,
                r * 0.15, -r * 0.6
        );
        rightEar.setFill(Color.web("#F7C9A0"));

        Circle leftEye = dot(-r * 0.22, -r * 0.05, r * 0.08, "#3d3458");
        Circle rightEye = dot(r * 0.22, -r * 0.05, r * 0.08, "#3d3458");

        Polygon snout = new Polygon(
                -r * 0.18, r * 0.12,
                r * 0.18, r * 0.12,
                0, r * 0.4
        );
        snout.setFill(Color.web("#FFFFFF"));
        snout.setOpacity(0.85);

        Circle noseTip = dot(0, r * 0.32, r * 0.06, "#3d3458");

        return new javafx.scene.Group(leftEar, rightEar, snout, leftEye, rightEye, noseTip);
    }

    // ── Помощни методи ───────────────────────────────────────────────────
    private static Circle dot(double x, double y, double radius, String colorHex) {
        Circle c = new Circle(radius);
        c.setTranslateX(x);
        c.setTranslateY(y);
        c.setFill(Color.web(colorHex));
        return c;
    }

    private static Arc smileArc(double r, String colorHex) {
        Arc arc = new Arc(0, r * 0.05, r * 0.32, r * 0.22, 200, 140);
        arc.setType(ArcType.OPEN);
        arc.setFill(Color.TRANSPARENT);
        arc.setStroke(Color.web(colorHex));
        arc.setStrokeWidth(Math.max(1.5, r * 0.06));
        return arc;
    }
}
