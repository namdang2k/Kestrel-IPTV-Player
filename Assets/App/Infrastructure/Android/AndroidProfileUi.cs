#if UNITY_ANDROID && !UNITY_EDITOR
using System;
using System.Collections.Generic;
using Newtonsoft.Json;
using Newtonsoft.Json.Linq;
using StbEmu.Profiles;
using StbEmu.UI;

namespace StbEmu.Android
{
    /// <summary>
    /// Native home in StbHost (Profile/Setting tabs, list, editor dialog). Java renders and keeps the draft text;
    /// this side owns the Profile objects and raises "ui.profile.*" events as <see cref="IProfileUi"/> events.
    /// </summary>
    public sealed class AndroidProfileUi : IProfileUi
    {
        public event Action Add;
        public event Action<Profile> Edit;
        public event Action<Profile> Delete;
        public event Action<Profile> Selected;
        public event Action<Profile> Saved;
        public event Action Cancelled;

        readonly AndroidStbHost host;
        readonly JObject app;
        IReadOnlyList<Profile> current = Array.Empty<Profile>();
        Profile editing; // null = no editor open; a stale save/cancel from a closed editor is dropped

        public AndroidProfileUi(AndroidStbHost host, string appName, string appVersion)
        {
            this.host = host;
            app = new JObject { ["name"] = appName, ["version"] = appVersion };
            host.NativeEvent += OnNativeEvent;
        }

        public void Show(IReadOnlyList<Profile> profiles)
        {
            current = profiles;
            editing = null;
            var list = new JArray();
            foreach (var p in profiles)
                list.Add(new JObject
                {
                    ["id"] = p.ProfileId, ["name"] = p.Name, ["host"] = Host(p.PortalUrl), ["mac"] = p.Identity?.Mac ?? "",
                });
            host.ShowProfileHome(new JObject { ["app"] = app, ["profiles"] = list }.ToString(Formatting.None));
        }

        public void ShowEdit(Profile profile)
        {
            editing = ProfileForm.Copy(profile);
            host.ShowProfileEditor(ProfileForm.ToForm(editing, profile == null ? "Add profile" : "Edit profile").ToString(Formatting.None));
        }

        public void ShowErrors(IEnumerable<string> messages) => host.SetProfileEditorErrors(new JArray(messages).ToString(Formatting.None));

        public void Hide()
        {
            editing = null;
            host.HideProfileHome();
        }

        void OnNativeEvent(string type, string data)
        {
            if (!type.StartsWith("ui.profile.", StringComparison.Ordinal)) return;
            switch (type.Substring("ui.profile.".Length))
            {
                case "add": Add?.Invoke(); break;
                case "edit": WithProfile(data, p => Edit?.Invoke(p)); break;
                case "delete": WithProfile(data, p => Delete?.Invoke(p)); break;
                case "connect": WithProfile(data, p => Selected?.Invoke(p)); break;
                case "cancel":
                    if (editing == null) return;
                    editing = null;
                    Cancelled?.Invoke();
                    break;
                case "save":
                    if (editing == null) return;
                    JObject form;
                    try { form = JObject.Parse(data); }
                    catch (JsonException e) { ShowErrors(new[] { "Could not read the form: " + e.Message }); return; }
                    ProfileForm.Apply(editing, form);
                    Saved?.Invoke(editing);
                    break;
            }
        }

        void WithProfile(string id, Action<Profile> action)
        {
            foreach (var p in current)
                if (p.ProfileId == id) { action(p); return; }
        }

        static string Host(string url) => Uri.TryCreate(url, UriKind.Absolute, out var uri) ? uri.Host : url;
    }
}
#endif
