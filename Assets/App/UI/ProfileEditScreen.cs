using System;
using System.Collections.Generic;
using Newtonsoft.Json;
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
        readonly InputField name, url, mac, serial, model, deviceId, deviceId2, signature;
        readonly Text sendDeviceIdLabel;
        readonly Text errors;
        readonly ScrollRect scroll;
        Profile editing;
        bool sendDeviceId;

        public ProfileEditScreen(Transform canvas)
        {
            root = UiKit.Require<RectTransform>(canvas, "EditProfile");
            title = UiKit.Require<Text>(root, "Title");
            scroll = UiKit.Require<ScrollRect>(root, "Scroll");
            var form = scroll.content;
            name = UiKit.Require<InputField>(form, "Name");
            url = UiKit.Require<InputField>(form, "PortalUrl");
            mac = UiKit.Require<InputField>(form, "Mac");
            serial = UiKit.Require<InputField>(form, "Serial");
            model = UiKit.Require<InputField>(form, "Model");
            deviceId = UiKit.Require<InputField>(form, "DeviceId");
            deviceId2 = UiKit.Require<InputField>(form, "DeviceId2");
            signature = UiKit.Require<InputField>(form, "Signature");
            UiKit.Require<Button>(form, "RandomMac").onClick.AddListener(() => mac.text = MacAddress.Generate());
            var toggle = UiKit.Require<Button>(form, "SendDeviceId");
            toggle.onClick.AddListener(ToggleSendDeviceId);
            sendDeviceIdLabel = toggle.GetComponentInChildren<Text>(true);
            UiKit.Require<Button>(form, "DeviceIdFromMac").onClick.AddListener(DeviceIdFromMac);
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
            editing = source == null ? new Profile() : JsonConvert.DeserializeObject<Profile>(JsonConvert.SerializeObject(source));
            title.text = source == null ? "Add profile" : "Edit profile";
            var id = editing.Identity;
            name.text = editing.Name;
            url.text = editing.PortalUrl;
            mac.text = id.Mac;
            serial.text = id.SerialNumber;
            model.text = id.Model;
            deviceId.text = id.DeviceId;
            deviceId2.text = id.DeviceId2;
            signature.text = id.Signature;
            sendDeviceId = source == null || id.SendDeviceId;
            RefreshToggle();
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
            editing.PortalUrl = url.text.Trim();
            var id = editing.Identity;
            id.Mac = mac.text.Trim();
            id.SerialNumber = serial.text.Trim();
            id.Model = model.text.Trim();
            id.SendDeviceId = sendDeviceId;
            id.DeviceId = deviceId.text.Trim();
            id.DeviceId2 = deviceId2.text.Trim();
            id.Signature = signature.text.Trim();
            Saved?.Invoke(editing);
        }

        void DeviceIdFromMac()
        {
            if (MacAddress.Normalize(mac.text) == null) ShowErrors(new[] { "Enter a valid MAC first (or tap Random MAC)." });
            else deviceId.text = MacAddress.ToDeviceId(mac.text);
        }

        void ToggleSendDeviceId()
        {
            sendDeviceId = !sendDeviceId;
            RefreshToggle();
        }

        void RefreshToggle() => sendDeviceIdLabel.text = "Send Device ID: " + (sendDeviceId ? "On" : "Off");
    }
}
