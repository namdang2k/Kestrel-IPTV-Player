using Newtonsoft.Json;
using Newtonsoft.Json.Linq;
using StbEmu.Profiles;

namespace StbEmu.UI
{
    /// <summary>
    /// Editor draft helpers: deep-copy a stored profile and write only the form's fields onto it, so settings the form
    /// does not show (Playback, future fields) survive an edit. Same fields as ProfileEditScreen.Save.
    /// </summary>
    public static class ProfileForm
    {
        public static Profile Copy(Profile source) =>
            source == null ? new Profile() : JsonConvert.DeserializeObject<Profile>(JsonConvert.SerializeObject(source));

        /// <summary>Form payload for the native editor; keys match <see cref="Apply"/>.</summary>
        public static JObject ToForm(Profile profile, string title) => new JObject
        {
            ["title"] = title,
            ["name"] = profile.Name,
            ["serverUrl"] = profile.ServerUrl,
            ["username"] = profile.Username,
            ["password"] = profile.Password,
        };

        /// <summary>Writes the keys present in <paramref name="form"/> (trimmed, except the password); missing or mistyped keys keep the current value.</summary>
        public static void Apply(Profile target, JObject form)
        {
            target.Name = Text(form, "name", target.Name);
            target.ServerUrl = Text(form, "serverUrl", target.ServerUrl);
            target.Username = Text(form, "username", target.Username);
            target.Password = Raw(form, "password", target.Password);
        }

        static string Text(JObject form, string key, string current) => Raw(form, key, current)?.Trim();

        static string Raw(JObject form, string key, string current) =>
            form.TryGetValue(key, out var value) && value.Type == JTokenType.String ? (string)value : current;
    }
}
