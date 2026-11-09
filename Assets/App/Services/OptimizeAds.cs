using System;

namespace StbEmu.Services
{
    // Remote Config key "OptimizeAds": a JSON string, e.g. {"showInterOnPortalLoad":true}.
    // Missing keys keep these defaults; add new switches here.
    [Serializable]
    public sealed class OptimizeAds
    {
        /// <summary>Allow an interstitial once a portal load finishes, whether it succeeded or failed.</summary>
        public bool showInterOnPortalLoad = false;

#if UNITY_EDITOR
        // The values are the class defaults (there is no asset); edit the file before pasting it into the console.
        [UnityEditor.MenuItem("StbEmu/Export OptimizeAds JSON")]
        static void ExportJson()
        {
            var path = UnityEditor.EditorUtility.SaveFilePanel("Export OptimizeAds", "", FirebaseKeyParams.OptimizeAds, "json");
            if (string.IsNullOrEmpty(path)) return;
            System.IO.File.WriteAllText(path, UnityEngine.JsonUtility.ToJson(new OptimizeAds(), true), new System.Text.UTF8Encoding(false));
            UnityEditor.EditorUtility.RevealInFinder(path);
            UnityEngine.Debug.Log("[OptimizeAds] Exported " + path);
        }
#endif
    }
}
