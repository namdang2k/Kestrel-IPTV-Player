using System.Linq;
using Cysharp.Threading.Tasks;
using Sirenix.OdinInspector;
using StbEmu.Portal;
using StbEmu.Profiles;
using StbEmu.Storage;
using UnityEngine;

namespace StbEmu.Dev
{
    /// <summary>
    /// Drop on any GameObject in a scene to smoke-test profile storage + portal bridge.
    /// Connect: Odin button in Play mode, or a UI Button's OnClick → PortalSmokeTest.Connect.
    /// Editor: only the profile part runs (the native WebView exists on Android only). Device: opens the portal and logs to logcat.
    /// Portal requests/responses (handshake, get_profile…): Development Build, then chrome://inspect on the PC → Network tab.
    /// </summary>
    public sealed class PortalSmokeTest : MonoBehaviour
    {
        [SerializeField] string portalUrl = "http://portal.example.test/c/";
        [SerializeField] string mac = ""; // empty = keep saved MAC, or generate 00:1A:79:xx:xx:xx once

        [Header("Identity (applied to the saved profile on every connect)")]
        [SerializeField] string serialNumber = "";
        [SerializeField] string model = "MAG250";
        [SerializeField] bool sendDeviceId = true;
        [SerializeField] string deviceId = ""; // empty = SHA256(MAC), like StbEmu's MAC-based device ID
        [SerializeField] string deviceId2 = ""; // empty = derived from deviceId by the facade
        [SerializeField] string signature = ""; // empty = HMAC-SHA256(token, deviceId) by the facade

#if UNITY_ANDROID && !UNITY_EDITOR
        Android.AndroidStbHost host;
        Android.AndroidPortalView view;
#endif

        [Space, SerializeField] bool showInputPanel = true;

        [Button(ButtonSizes.Large), DisableInEditorMode]
        public void Connect() => ConnectAsync().Forget();

        // ponytail: IMGUI dev panel (touch/mouse; no D-pad focus on TV). Swap for uGUI TMP_InputFields if it becomes real UI.
        void OnGUI()
        {
            if (!showInputPanel) return;
            var scale = Screen.height / 720f;
            GUI.matrix = Matrix4x4.Scale(new Vector3(scale, scale, 1));

            GUILayout.BeginArea(new Rect(20, 20, Mathf.Min(900, Screen.width / scale - 40), 680), GUI.skin.box);
            portalUrl = Field("Portal URL", portalUrl);
            mac = Field("MAC", mac);
            serialNumber = Field("Serial", serialNumber);
            model = Field("Model", model);
            sendDeviceId = GUILayout.Toggle(sendDeviceId, " Send Device ID");
            deviceId = Field("Device ID", deviceId);
            deviceId2 = Field("Device ID 2", deviceId2);
            signature = Field("Signature", signature);
            if (GUILayout.Button("Connect", GUILayout.Height(60))) Connect();
            GUILayout.EndArea();
        }

        static string Field(string label, string value)
        {
            GUILayout.BeginHorizontal();
            GUILayout.Label(label, GUILayout.Width(140));
            value = GUILayout.TextField(value ?? "", GUILayout.Height(36));
            GUILayout.EndHorizontal();
            return value;
        }

        async UniTaskVoid ConnectAsync()
        {
            var ct = destroyCancellationToken;
            var manager = new ProfileManager(new JsonProfileRepository(Application.persistentDataPath));
            await manager.LoadAsync(ct);

            var profile = manager.Profiles.FirstOrDefault(p => p.PortalUrl == portalUrl)
                ?? await manager.AddAsync(new Profile { Name = "Smoke test", PortalUrl = portalUrl, Identity = { Mac = mac } }, ct);

            var id = profile.Identity;
            if (!string.IsNullOrWhiteSpace(mac)) id.Mac = mac;
            mac = id.Mac;
            if (string.IsNullOrWhiteSpace(deviceId)) deviceId = MacAddress.ToDeviceId(id.Mac); // shown back in the inspector
            id.SerialNumber = serialNumber;
            id.Model = model;
            id.SendDeviceId = sendDeviceId;
            id.DeviceId = deviceId;
            id.DeviceId2 = deviceId2;
            id.Signature = signature;
            await manager.UpdateAsync(profile, ct);
            Debug.Log($"[Smoke] profile {profile.Name} mac={id.Mac} sn={id.SerialNumber} deviceId={id.DeviceId} sendDeviceId={id.SendDeviceId} ({Application.persistentDataPath})");

#if UNITY_ANDROID && !UNITY_EDITOR
            if (host == null)
            {
                host = new Android.AndroidStbHost();
                host.NativeEvent += (type, data) => Debug.Log($"[Smoke] native {type}: {data}");
                view = new Android.AndroidPortalView(host);
                view.MessageReceived += m => Debug.Log($"[Smoke] portal {m.Type} {m.Payload.ToString(Newtonsoft.Json.Formatting.None)}");
                view.Error += e => Debug.LogError($"[Smoke] portal error {e}");
            }

            await view.OpenAsync(new PortalSession(profile), ct).Timeout(System.TimeSpan.FromSeconds(30));
            Debug.Log("[Smoke] bridge.hello received: facade is live in the portal page");
#else
            Debug.Log("[Smoke] portal bridge skipped: native WebView only runs on an Android device");
#endif
        }

#if UNITY_ANDROID && !UNITY_EDITOR
        void OnDestroy() => host?.Dispose();
#endif
    }
}
