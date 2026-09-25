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
            root = UiKit.Screen(canvas, "EditProfile");
            title = UiKit.Label(root, "", 44);
            UiKit.Height(title.gameObject, 70);

            var form = UiKit.ScrollList(root);
            scroll = form.GetComponentInParent<ScrollRect>();
            name = UiKit.Input(form, "Tên");
            url = UiKit.Input(form, "Portal URL (http://host/c/)");
            mac = UiKit.Input(form, "MAC (trống = tự sinh)");
            UiKit.Button(form, "Sinh MAC ngẫu nhiên", () => mac.text = MacAddress.Generate());
            serial = UiKit.Input(form, "Serial");
            model = UiKit.Input(form, "Model");
            sendDeviceIdLabel = UiKit.Button(form, "", ToggleSendDeviceId).GetComponentInChildren<Text>();
            deviceId = UiKit.Input(form, "Device ID");
            UiKit.Button(form, "Device ID từ MAC", DeviceIdFromMac);
            deviceId2 = UiKit.Input(form, "Device ID 2 (trống = tự tính)");
            signature = UiKit.Input(form, "Signature (trống = tự tính)");

            errors = UiKit.Label(form, "", 26);
            errors.color = new Color(1f, 0.5f, 0.5f);

            var actions = new GameObject("FixedActions", typeof(RectTransform)).GetComponent<RectTransform>();
            actions.SetParent(root, false);
            actions.gameObject.AddComponent<LayoutElement>().ignoreLayout = true;
            var cancel = UiKit.Button(actions, "Huỷ", () => Cancelled?.Invoke());
            var save = UiKit.Button(actions, "Lưu", Save, primary: true);
            actions.gameObject.AddComponent<ProfileActionLayout>().Initialize(root, cancel, save);
        }

        /// <param name="source">null = new profile.</param>
        public void Show(Profile source)
        {
            editing = source == null ? new Profile() : JsonConvert.DeserializeObject<Profile>(JsonConvert.SerializeObject(source));
            title.text = source == null ? "Thêm profile" : "Sửa profile";
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
            if (MacAddress.Normalize(mac.text) == null) ShowErrors(new[] { "Nhập MAC hợp lệ trước (hoặc bấm Sinh MAC)." });
            else deviceId.text = MacAddress.ToDeviceId(mac.text);
        }

        void ToggleSendDeviceId()
        {
            sendDeviceId = !sendDeviceId;
            RefreshToggle();
        }

        void RefreshToggle() => sendDeviceIdLabel.text = "Gửi Device ID: " + (sendDeviceId ? "Bật" : "Tắt");
    }
}
