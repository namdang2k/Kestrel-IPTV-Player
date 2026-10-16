#if UNITY_EDITOR
using System;
using System.Collections.Generic;
using System.Text;
using UnityEditor;
using UnityEngine;

namespace StbEmu.Services
{
    /// <summary>
    /// Writes a tier preset of <see cref="FirebaseKeyParams.AdMob_Config"/>: the AdMobSettings asset × the tier's multipliers
    /// (<see cref="MonetizationPolicy.TierMultiplier"/>). IDs, test flag and legacy fields are copied unchanged; the asset is never modified.
    /// The operator assigns each file to Firebase country conditions by hand.
    /// </summary>
    static class AdMobTierConfigExporter
    {
        [MenuItem("StbEmu/admod_setting_tier_1")] static void Tier1() => Export(1);
        [MenuItem("StbEmu/admod_setting_tier_2")] static void Tier2() => Export(2);
        [MenuItem("StbEmu/admod_setting_tier_3")] static void Tier3() => Export(3);

        // Play mode may have overwritten the asset with remote values.
        [MenuItem("StbEmu/admod_setting_tier_1", true)]
        [MenuItem("StbEmu/admod_setting_tier_2", true)]
        [MenuItem("StbEmu/admod_setting_tier_3", true)]
        static bool CanExport() => !EditorApplication.isPlayingOrWillChangePlaymode;

        static void Export(int tier)
        {
            var settings = Resources.Load<AdMobSettings>(AdMobSettings.ResourcePath);
            if (settings == null) { Debug.LogError("[TierExport] AdMobSettings not found at Resources/" + AdMobSettings.ResourcePath); return; }
            var config = AdMobRemoteConfig.From(settings);
            var asset = config.Timings();
            var errors = new List<string>();
            var values = MonetizationPolicy.Tier(asset, tier, errors);
            if (values == null) { Debug.LogError($"[TierExport] Tier {tier} not exported:\n" + string.Join("\n", errors)); return; }

            var log = new StringBuilder($"[TierExport] Tier {tier} (base × multiplier = output):\n");
            for (int f = 0; f < MonetizationPolicy.FieldCount; f++)
            {
                log.Append($"{MonetizationPolicy.Names[f]}: {asset[f]} × {MonetizationPolicy.TierMultiplier[tier - 1][f]} = {values[f]}");
                bool viewingOff = f == MonetizationPolicy.RewardedViewing && values[f] == 0;
                if (!viewingOff && (values[f] < MonetizationPolicy.Min[f] || values[f] > MonetizationPolicy.Max[f]))
                    log.Append($"  ⚠ runtime clamps to [{MonetizationPolicy.Min[f]}, {MonetizationPolicy.Max[f]}]");
                log.Append('\n');
            }
            config.SetTimings(values);

            var path = EditorUtility.SaveFilePanel($"Export AdMob config tier {tier}", "", $"AdMob_Config_tier_{tier}", "json");
            if (string.IsNullOrEmpty(path)) return;
            try { System.IO.File.WriteAllText(path, JsonUtility.ToJson(config, true), new UTF8Encoding(false)); }
            catch (Exception error) { Debug.LogError("[TierExport] Could not write " + path + ": " + error.Message); return; }
            Debug.Log(log.Append("Saved ").Append(path).ToString());
            EditorUtility.RevealInFinder(path);
        }
    }
}
#endif
