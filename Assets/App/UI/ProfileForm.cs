using Newtonsoft.Json;
using Newtonsoft.Json.Linq;
using StbEmu.Profiles;

namespace StbEmu.UI
{
    /// <summary>
    /// Editor draft helpers: deep-copy a stored profile and write only the form's fields onto it, so settings the form
    /// does not show (Compatibility, Playback, DeviceIdMode, future fields) survive an edit. Same fields as ProfileEditScreen.Save.
    /// </summary>
    public static class ProfileForm
    {
        public static Profile Copy(Profile source) =>
            source == null ? new Profile() : JsonConvert.DeserializeObject<Profile>(JsonConvert.SerializeObject(source));

        /// <summary>Form payload for the native editor; keys match <see cref="Apply"/>.</summary>
        public static JObject ToForm(Profile profile, string title)
        {
            var id = profile.Identity;
            return new JObject
            {
                ["title"] = title,
                ["name"] = profile.Name,
                ["portalUrl"] = profile.PortalUrl,
                ["mac"] = id.Mac,
                ["serialNumber"] = id.SerialNumber,
                ["model"] = id.Model,
                ["sendDeviceId"] = id.SendDeviceId,
                ["deviceId"] = id.DeviceId,
                ["deviceId2"] = id.DeviceId2,
                ["signature"] = id.Signature,
                ["forceLoginPortal"] = profile.ForceLoginPortal,
            };
        }

        /// <summary>Writes the keys present in <paramref name="form"/> (strings trimmed); missing or mistyped keys keep the current value.</summary>
        public static void Apply(Profile target, JObject form)
        {
            var id = target.Identity;
            target.Name = Text(form, "name", target.Name);
            target.PortalUrl = Text(form, "portalUrl", target.PortalUrl);
            id.Mac = Text(form, "mac", id.Mac);
            id.SerialNumber = Text(form, "serialNumber", id.SerialNumber);
            id.Model = Text(form, "model", id.Model);
            id.SendDeviceId = Flag(form, "sendDeviceId", id.SendDeviceId);
            id.DeviceId = Text(form, "deviceId", id.DeviceId);
            id.DeviceId2 = Text(form, "deviceId2", id.DeviceId2);
            id.Signature = Text(form, "signature", id.Signature);
            target.ForceLoginPortal = Flag(form, "forceLoginPortal", target.ForceLoginPortal);
        }

        static string Text(JObject form, string key, string current) =>
            form.TryGetValue(key, out var value) && value.Type == JTokenType.String ? ((string)value).Trim() : current;

        static bool Flag(JObject form, string key, bool current) =>
            form.TryGetValue(key, out var value) && value.Type == JTokenType.Boolean ? (bool)value : current;
    }
}
