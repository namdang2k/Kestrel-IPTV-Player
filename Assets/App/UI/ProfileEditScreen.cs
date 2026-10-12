using System;
using System.Collections.Generic;
using StbEmu.Profiles;
using UnityEngine;
using UnityEngine.UI;

namespace StbEmu.UI
{
    /// <summary>Edits a copy of a profile; the caller saves it through ProfileManager and reports errors back.</summary>
    public sealed class ProfileEditScreen
    {
        public event Action<Profile> Saved;
        public event Action Cancelled;

        readonly RectTransform root;
        readonly Text title;
        readonly InputField name, url, username, password;
        readonly Text errors;
        readonly ScrollRect scroll;
        Profile editing;

        public ProfileEditScreen(Transform canvas)
        {
            root = UiKit.Require<RectTransform>(canvas, "EditProfile");
            title = UiKit.Require<Text>(root, "Title");
            scroll = UiKit.Require<ScrollRect>(root, "Scroll");
            var form = scroll.content;
            name = UiKit.Require<InputField>(form, "Name");
            // The prefab still carries the portal-era field names; they are relabelled instead of rebuilt.
            url = Relabel(UiKit.Require<InputField>(form, "PortalUrl"), "Server URL (http://host:port)");
            username = Relabel(UiKit.Require<InputField>(form, "Mac"), "Username");
            username.contentType = InputField.ContentType.Standard; // the prefab's Mac field restricts characters
            password = Relabel(UiKit.Require<InputField>(form, "Serial"), "Password");
            password.contentType = InputField.ContentType.Password;
            Hide(UiKit.Require<InputField>(form, "Model")); // User-Agent overrides are not user-editable
            Hide(UiKit.Require<InputField>(form, "DeviceId"));
            Hide(UiKit.Require<InputField>(form, "DeviceId2"));
            Hide(UiKit.Require<InputField>(form, "Signature"));
            Hide(UiKit.Require<Button>(form, "RandomMac"));
            Hide(UiKit.Require<Button>(form, "DeviceIdFromMac"));
            Hide(UiKit.Require<Button>(form, "SendDeviceId"));
            errors = UiKit.Require<Text>(form, "Errors");
            var actions = UiKit.Require<ProfileActionLayout>(root, "FixedActions");
            var cancel = UiKit.Require<Button>(actions.transform, "Cancel");
            var save = UiKit.Require<Button>(actions.transform, "Save");
            cancel.onClick.AddListener(() => Cancelled?.Invoke());
            save.onClick.AddListener(Save);
            actions.Initialize(root, cancel, save);
            Hide();
        }

        public void Show(Profile source)
        {
            editing = ProfileForm.Copy(source);
            title.text = source == null ? "Add profile" : "Edit profile";
            name.text = editing.Name;
            url.text = editing.ServerUrl;
            username.text = editing.Username;
            password.text = editing.Password;
            errors.text = "";
            root.gameObject.SetActive(true);
            Canvas.ForceUpdateCanvases();
            scroll.StopMovement();
            scroll.verticalNormalizedPosition = 1;
        }

        public void Hide() => root.gameObject.SetActive(false);

        public void ShowErrors(IEnumerable<string> messages)
        {
            errors.text = string.Join("\n", messages);
            Canvas.ForceUpdateCanvases();
            scroll.StopMovement();
            scroll.verticalNormalizedPosition = 0;
        }

        void Save()
        {
            editing.Name = name.text.Trim();
            editing.ServerUrl = url.text.Trim();
            editing.Username = username.text.Trim();
            editing.Password = password.text;
            Saved?.Invoke(editing);
        }

        /// <summary>Sets the placeholder and the caption Text right above the field, if any.</summary>
        static InputField Relabel(InputField field, string text)
        {
            if (field.placeholder is Text placeholder) placeholder.text = text;
            var caption = Caption(field);
            if (caption != null) caption.text = text;
            return field;
        }

        static void Hide(Component field)
        {
            var caption = Caption(field);
            if (caption != null) caption.gameObject.SetActive(false);
            field.gameObject.SetActive(false);
        }

        static Text Caption(Component field)
        {
            int index = field.transform.GetSiblingIndex();
            return index > 0 && field.transform.parent.GetChild(index - 1).TryGetComponent<Text>(out var label)
                && label.GetComponent<Selectable>() == null ? label : null;
        }
    }
}
