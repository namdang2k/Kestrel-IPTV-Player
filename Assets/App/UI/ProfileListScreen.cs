using System;
using System.Collections.Generic;
using StbEmu.Profiles;
using UnityEngine;
using UnityEngine.UI;

namespace StbEmu.UI
{
    public sealed class ProfileListScreen
    {
        public event Action<Profile> Selected;
        public event Action<Profile> Edit;
        public event Action<Profile> Delete;
        public event Action Add;

        readonly RectTransform root;
        readonly RectTransform list;

        public ProfileListScreen(Transform canvas)
        {
            root = UiKit.Require<RectTransform>(canvas, "Profiles");
            list = UiKit.Require<RectTransform>(root, "Scroll/Viewport/Content");
            UiKit.Require<Button>(root, "AddProfile").onClick.AddListener(() => Add?.Invoke());
            Hide();
        }

        public void Show(IReadOnlyList<Profile> profiles)
        {
            UiKit.Clear(list);
            foreach (var profile in profiles) AddRow(profile);
            root.gameObject.SetActive(true);
        }

        public void Hide() => root.gameObject.SetActive(false);

        void AddRow(Profile profile)
        {
            var row = UiKit.Row(list);
            UiKit.Height(row.gameObject, 130);

            var open = UiKit.Button(row, $"{profile.Name}", () => Selected?.Invoke(profile));
            var label = open.GetComponentInChildren<Text>();
            label.alignment = TextAnchor.MiddleLeft;
            label.rectTransform.offsetMin = new Vector2(24, 0);
            UiKit.Flexible(open.gameObject, width: 1);

            UiKit.Width(UiKit.Button(row, "Edit", () => Edit?.Invoke(profile)).gameObject, 140);

            // Two-tap delete instead of a dialog: first tap arms, second deletes.
            Button delete = null;
            delete = UiKit.Button(row, "Delete", () =>
            {
                var text = delete.GetComponentInChildren<Text>();
                if (text.text == "Sure?") Delete?.Invoke(profile);
                else text.text = "Sure?";
            }, danger: true);
            UiKit.Width(delete.gameObject, 140);
        }

        static string Host(string url) => Uri.TryCreate(url, UriKind.Absolute, out var uri) ? uri.Host : url;
    }
}
