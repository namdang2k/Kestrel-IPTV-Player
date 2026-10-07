using System;
using System.Collections.Generic;
using StbEmu.Profiles;

namespace StbEmu.UI
{
    /// <summary>Profile list + editor as one surface. AppFlow drives it; uGUI renders it in the Editor, Android views on device.</summary>
    public interface IProfileUi
    {
        event Action Add;
        event Action<Profile> Edit;
        event Action<Profile> Delete;
        event Action<Profile> Selected;
        /// <summary>A draft to save: a copy of the stored profile with the form applied, or a new profile.</summary>
        event Action<Profile> Saved;
        event Action Cancelled;

        void Show(IReadOnlyList<Profile> profiles);
        void ShowEdit(Profile profile);
        /// <summary>Validation/storage errors for the open editor; the draft stays.</summary>
        void ShowErrors(IEnumerable<string> messages);
        void Hide();
    }
}
