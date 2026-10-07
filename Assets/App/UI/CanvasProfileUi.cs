using System;
using System.Collections.Generic;
using StbEmu.Profiles;
using UnityEngine;

namespace StbEmu.UI
{
    /// <summary>uGUI implementation (Editor and non-Android): the existing list and edit screens behind one surface.</summary>
    public sealed class CanvasProfileUi : IProfileUi
    {
        readonly ProfileListScreen list;
        readonly ProfileEditScreen edit;

        public event Action Add { add => list.Add += value; remove => list.Add -= value; }
        public event Action<Profile> Edit { add => list.Edit += value; remove => list.Edit -= value; }
        public event Action<Profile> Delete { add => list.Delete += value; remove => list.Delete -= value; }
        public event Action<Profile> Selected { add => list.Selected += value; remove => list.Selected -= value; }
        public event Action<Profile> Saved { add => edit.Saved += value; remove => edit.Saved -= value; }
        public event Action Cancelled { add => edit.Cancelled += value; remove => edit.Cancelled -= value; }

        public CanvasProfileUi(Transform canvas)
        {
            list = new ProfileListScreen(canvas);
            edit = new ProfileEditScreen(canvas);
        }

        public void Show(IReadOnlyList<Profile> profiles)
        {
            edit.Hide();
            list.Show(profiles);
        }

        public void ShowEdit(Profile profile)
        {
            list.Hide();
            edit.Show(profile);
        }

        public void ShowErrors(IEnumerable<string> messages) => edit.ShowErrors(messages);

        public void Hide()
        {
            list.Hide();
            edit.Hide();
        }
    }
}
