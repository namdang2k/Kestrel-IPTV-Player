using UnityEngine;
using UnityEngine.Events;
using UnityEngine.EventSystems;
using UnityEngine.UI;

namespace StbEmu.UI
{
    /// <summary>
    /// Builds the few uGUI widgets the app needs from code.
    /// ponytail: code-built UI, no prefabs; move to prefabs when the look needs designer iteration.
    /// Legacy Text/InputField on purpose: the built-in dynamic font falls back to OS fonts (Vietnamese), TMP's default SDF font does not.
    /// </summary>
    public static class UiKit
    {
        public static readonly Color Background = new Color(0.08f, 0.09f, 0.11f);
        static readonly Color Surface = new Color(0.16f, 0.17f, 0.2f);
        static readonly Color Accent = new Color(0.2f, 0.45f, 0.9f);
        static readonly Color Danger = new Color(0.75f, 0.2f, 0.2f);
        const int FontSize = 32;
        const float RowHeight = 96;

        static Font font;
        static Font Font => font ? font : font = Resources.GetBuiltinResource<Font>("LegacyRuntime.ttf");

        public static Transform CreateCanvas()
        {
            if (EventSystem.current == null)
                new GameObject("EventSystem", typeof(EventSystem), typeof(StandaloneInputModule));

            var go = new GameObject("AppCanvas", typeof(Canvas), typeof(CanvasScaler), typeof(GraphicRaycaster));
            go.GetComponent<Canvas>().renderMode = RenderMode.ScreenSpaceOverlay;
            var scaler = go.GetComponent<CanvasScaler>();
            scaler.uiScaleMode = CanvasScaler.ScaleMode.ScaleWithScreenSize;
            scaler.referenceResolution = new Vector2(1080, 1920);
            scaler.matchWidthOrHeight = 0.5f;
            return go.transform;
        }

        /// <summary>Full-screen panel with a vertical layout; starts hidden.</summary>
        public static RectTransform Screen(Transform canvas, string name)
        {
            var rt = Rect(name, canvas);
            Stretch(rt);
            rt.gameObject.AddComponent<Image>().color = Background;
            var layout = rt.gameObject.AddComponent<VerticalLayoutGroup>();
            layout.padding = new RectOffset(40, 40, 60, 40);
            layout.spacing = 20;
            layout.childControlWidth = layout.childControlHeight = true;
            layout.childForceExpandWidth = true;
            layout.childForceExpandHeight = false;
            rt.gameObject.SetActive(false);
            return rt;
        }

        public static Text Label(Transform parent, string text, int size = FontSize, TextAnchor anchor = TextAnchor.MiddleLeft)
        {
            var t = Rect("Label", parent).gameObject.AddComponent<Text>();
            t.font = Font;
            t.fontSize = size;
            t.color = Color.white;
            t.alignment = anchor;
            t.horizontalOverflow = HorizontalWrapMode.Wrap;
            t.verticalOverflow = VerticalWrapMode.Overflow;
            t.text = text;
            return t;
        }

        public static Button Button(Transform parent, string text, UnityAction onClick, bool primary = false, bool danger = false)
        {
            var rt = Rect("Button", parent);
            rt.gameObject.AddComponent<Image>().color = danger ? Danger : primary ? Accent : Surface;
            var button = rt.gameObject.AddComponent<Button>();
            button.onClick.AddListener(onClick);
            var label = Label(rt, text, FontSize, TextAnchor.MiddleCenter);
            Stretch(label.rectTransform);
            Height(rt.gameObject, RowHeight);
            return button;
        }

        public static InputField Input(Transform parent, string label, InputField.ContentType type = InputField.ContentType.Standard)
        {
            Height(Label(parent, label, 26).gameObject, 34);
            var rt = Rect("Input", parent);
            rt.gameObject.AddComponent<Image>().color = Surface;
            var input = rt.gameObject.AddComponent<InputField>();
            var text = Label(rt, "");
            text.supportRichText = false;
            Stretch(text.rectTransform, 20);
            var placeholder = Label(rt, label);
            placeholder.color = new Color(1, 1, 1, 0.3f);
            Stretch(placeholder.rectTransform, 20);
            input.textComponent = text;
            input.placeholder = placeholder;
            input.contentType = type;
            input.lineType = InputField.LineType.SingleLine;
            Height(rt.gameObject, RowHeight);
            return input;
        }

        /// <summary>Horizontal row; children share width by <see cref="Flexible"/>.</summary>
        public static RectTransform Row(Transform parent)
        {
            var rt = Rect("Row", parent);
            var layout = rt.gameObject.AddComponent<HorizontalLayoutGroup>();
            layout.spacing = 16;
            layout.childControlWidth = layout.childControlHeight = true;
            layout.childForceExpandWidth = false;
            layout.childForceExpandHeight = true;
            Height(rt.gameObject, RowHeight);
            return rt;
        }

        /// <summary>Vertical scroll area filling the remaining height; returns the content to add children to.</summary>
        public static RectTransform ScrollList(Transform parent)
        {
            var scroll = Rect("Scroll", parent);
            Flexible(scroll.gameObject, height: 1);
            var scrollRect = scroll.gameObject.AddComponent<ScrollRect>();
            scrollRect.horizontal = false;
            scrollRect.movementType = ScrollRect.MovementType.Clamped;

            var viewport = Rect("Viewport", scroll);
            Stretch(viewport);
            viewport.gameObject.AddComponent<RectMask2D>();

            var content = Rect("Content", viewport);
            content.anchorMin = new Vector2(0, 1);
            content.anchorMax = new Vector2(1, 1);
            content.pivot = new Vector2(0.5f, 1);
            content.sizeDelta = Vector2.zero;
            var layout = content.gameObject.AddComponent<VerticalLayoutGroup>();
            layout.spacing = 16;
            layout.childControlWidth = layout.childControlHeight = true;
            layout.childForceExpandWidth = true;
            layout.childForceExpandHeight = false;
            content.gameObject.AddComponent<ContentSizeFitter>().verticalFit = ContentSizeFitter.FitMode.PreferredSize;

            scrollRect.viewport = viewport;
            scrollRect.content = content;
            return content;
        }

        public static void Flexible(GameObject go, float width = 0, float height = 0)
        {
            var element = Element(go);
            element.flexibleWidth = width;
            element.flexibleHeight = height;
        }

        public static void Height(GameObject go, float height) => Element(go).preferredHeight = height;

        public static void Width(GameObject go, float width) => Element(go).preferredWidth = width;

        // TryGetComponent, not GetComponent ?? AddComponent: in the Editor a missing component is a non-null "fake null".
        static LayoutElement Element(GameObject go) => go.TryGetComponent<LayoutElement>(out var e) ? e : go.AddComponent<LayoutElement>();

        public static void Clear(Transform parent)
        {
            for (var i = parent.childCount - 1; i >= 0; i--) Object.Destroy(parent.GetChild(i).gameObject);
        }

        static RectTransform Rect(string name, Transform parent)
        {
            var rt = new GameObject(name, typeof(RectTransform)).GetComponent<RectTransform>();
            rt.SetParent(parent, false);
            return rt;
        }

        static void Stretch(RectTransform rt, float inset = 0)
        {
            rt.anchorMin = Vector2.zero;
            rt.anchorMax = Vector2.one;
            rt.offsetMin = new Vector2(inset, 0);
            rt.offsetMax = new Vector2(-inset, 0);
        }
    }
}
