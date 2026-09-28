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
            root = UiKit.Require<RectTransform>(canvas, "Status");
            title = UiKit.Require<Text>(root, "Title");
            message = UiKit.Require<Text>(root, "Message");
            primary = UiKit.Require<Button>(root, "Primary");
            secondary = UiKit.Require<Button>(root, "Secondary");
            primary.onClick.AddListener(() => onPrimary?.Invoke());
            secondary.onClick.AddListener(() => onSecondary?.Invoke());
            Hide();
        }

        public void ShowConnecting(string profileName, Action cancel) =>
            Show("Connecting…", profileName, "Cancel", cancel, null, null);

        public void ShowError(string text, Action retry, Action back) =>
            Show("Could not open the portal", text, "Retry", retry, "Back", back);

        public void ShowRewardPrompt(string text, Action watch, Action exit, bool canWatch, bool canExit = true)
        {
            Show("Continue watching", text, "Watch", watch, "Exit", exit);
            primary.interactable = canWatch;
            secondary.interactable = canExit;
        }

        public void Hide() => root.gameObject.SetActive(false);

        void Show(string titleText, string messageText, string primaryText, Action primaryAction, string secondaryText, Action secondaryAction)
        {
            primary.interactable = secondary.interactable = true;
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
