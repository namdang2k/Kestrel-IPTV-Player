using UnityEngine;
using UnityEngine.UI;

namespace StbEmu.UI
{
    /// <summary>Footer measured in screen pixels, independent of the app CanvasScaler.</summary>
    public sealed class ProfileActionLayout : MonoBehaviour
    {
        RectTransform root, footer;
        Button cancel, save;
        Canvas canvas;
        Vector2 lastScreen;
        Rect lastSafe;
        float lastScale;

        public void Initialize(RectTransform screen, Button cancelButton, Button saveButton)
        {
            root = screen;
            footer = (RectTransform)transform;
            cancel = cancelButton;
            save = saveButton;
            canvas = GetComponentInParent<Canvas>(true); // screen starts inactive
            Refresh();
        }

        void LateUpdate()
        {
            if (canvas && (lastScreen != new Vector2(Screen.width, Screen.height)
                || lastSafe != Screen.safeArea || !Mathf.Approximately(lastScale, canvas.scaleFactor))) Refresh();
        }

        void Refresh()
        {
            if (!canvas) return;
            lastScreen = new Vector2(Screen.width, Screen.height);
            lastSafe = Screen.safeArea;
            lastScale = Mathf.Max(0.01f, canvas.scaleFactor);
            const float gap = 16, height = 50, inset = 16;
            float width = Mathf.Min(200, Mathf.Max(1, (lastSafe.width - inset * 2 - gap) / 2));
            footer.anchorMin = footer.anchorMax = new Vector2(0.5f, 0);
            footer.pivot = new Vector2(0.5f, 0);
            footer.sizeDelta = new Vector2((width * 2 + gap) / lastScale, height / lastScale);
            footer.anchoredPosition = new Vector2((lastSafe.center.x - Screen.width / 2f) / lastScale,
                (lastSafe.yMin + inset) / lastScale);
            Position(cancel, 0, width, height);
            Position(save, 1, width, height);
            var layout = root.GetComponent<VerticalLayoutGroup>();
            layout.padding.bottom = Mathf.CeilToInt((lastSafe.yMin + inset + height + 16) / lastScale);
            LayoutRebuilder.MarkLayoutForRebuild(root);
        }

        void Position(Button button, float x, float width, float height)
        {
            var rect = (RectTransform)button.transform;
            rect.anchorMin = rect.anchorMax = new Vector2(x, 0);
            rect.pivot = new Vector2(x, 0);
            rect.anchoredPosition = Vector2.zero;
            rect.sizeDelta = new Vector2(width, height) / lastScale;
            button.GetComponentInChildren<Text>().fontSize = Mathf.Max(1, Mathf.RoundToInt(20 / lastScale));
        }
    }
}
