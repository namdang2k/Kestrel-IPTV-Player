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
#if UNITY_ANDROID && !UNITY_EDITOR
        Android.AndroidStbHost nativeHost;
        public void UseNativeLoading(Android.AndroidStbHost host) => nativeHost = host;
#endif

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

        public void ShowConnecting(string profileName, Action cancel)
        {
#if UNITY_ANDROID && !UNITY_EDITOR
            if (nativeHost != null)
            {
                root.gameObject.SetActive(false);
                nativeHost.ShowPortalLoading(profileName);
                return;
            }
#endif
            Show("Connecting…", profileName, "Cancel", cancel, null, null);
        }

        public void ShowError(string text, Action retry, Action back)
        {
#if UNITY_ANDROID && !UNITY_EDITOR
            if (nativeHost != null)
            {
                root.gameObject.SetActive(false);
                onPrimary = retry;
                onSecondary = back;
                nativeHost.ShowPortalError(text);
                return;
            }
#endif
            Show("Could not open the portal", text, "Retry", retry, "Back", back);
        }

        public void Retry() => onPrimary?.Invoke();
        public void Back() => onSecondary?.Invoke();

        public void Hide()
        {
#if UNITY_ANDROID && !UNITY_EDITOR
            nativeHost?.HidePortalLoading();
#endif
            root.gameObject.SetActive(false);
        }

        void Show(string titleText, string messageText, string primaryText, Action primaryAction, string secondaryText, Action secondaryAction)
        {
            Hide();
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
