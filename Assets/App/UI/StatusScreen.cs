using System;
using UnityEngine;
using UnityEngine.UI;

namespace StbEmu.UI
{
    /// <summary>"Connecting…" (one button: cancel) and error (retry + back) share this screen.</summary>
    public sealed class StatusScreen
    {
        readonly RectTransform root;
        readonly Text title, message;
        readonly Button primary, secondary;
        Action onPrimary, onSecondary;

        public StatusScreen(Transform canvas)
        {
            root = UiKit.Screen(canvas, "Status");
            UiKit.Flexible(UiKit.Label(root, "").gameObject, height: 1); // top spacer
            title = UiKit.Label(root, "", 44, TextAnchor.MiddleCenter);
            message = UiKit.Label(root, "", 30, TextAnchor.MiddleCenter);
            UiKit.Flexible(UiKit.Label(root, "").gameObject, height: 1); // bottom spacer
            primary = UiKit.Button(root, "", () => onPrimary?.Invoke(), primary: true);
            secondary = UiKit.Button(root, "", () => onSecondary?.Invoke());
        }

        public void ShowConnecting(string profileName, Action cancel) =>
            Show("Đang kết nối…", profileName, "Huỷ", cancel, null, null);

        public void ShowError(string text, Action retry, Action back) =>
            Show("Không mở được portal", text, "Thử lại", retry, "Quay lại", back);

        public void Hide() => root.gameObject.SetActive(false);

        void Show(string titleText, string messageText, string primaryText, Action primaryAction, string secondaryText, Action secondaryAction)
        {
            title.text = titleText;
            message.text = messageText;
            primary.GetComponentInChildren<Text>().text = primaryText;
            onPrimary = primaryAction;
            secondary.gameObject.SetActive(secondaryText != null);
            if (secondaryText != null) secondary.GetComponentInChildren<Text>().text = secondaryText;
            onSecondary = secondaryAction;
            root.gameObject.SetActive(true);
        }
    }
}
