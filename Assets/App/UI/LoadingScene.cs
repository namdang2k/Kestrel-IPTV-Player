using System.Collections;
using UnityEngine;
using UnityEngine.SceneManagement;
using UnityEngine.UI;

namespace StbEmu.UI
{
    /// <summary>Attach to an active GameObject in the Loading scene.</summary>
    public sealed class LoadingScene : MonoBehaviour
    {
        [SerializeField] string mainSceneName = "Main";
        [SerializeField] Slider progressBar;

        IEnumerator Start()
        {
            if (string.IsNullOrWhiteSpace(mainSceneName) ||
                !Application.CanStreamedLevelBeLoaded(mainSceneName))
            {
                Debug.LogError($"[LoadingScene] Add scene '{mainSceneName}' to the build scene list and enable it.", this);
                yield break;
            }

            if (progressBar != null)
            {
                progressBar.minValue = 0f;
                progressBar.maxValue = 1f;
                progressBar.wholeNumbers = false;
                progressBar.value = 0f;
            }

            // Give the loading UI a frame to appear before starting the load.
            yield return null;

            AsyncOperation loading = SceneManager.LoadSceneAsync(mainSceneName, LoadSceneMode.Single);
            if (loading == null)
            {
                Debug.LogError($"[LoadingScene] Could not load scene '{mainSceneName}'.", this);
                yield break;
            }

            while (!loading.isDone)
            {
                // Unity reports scene loading progress from 0 to 0.9 before activation.
                if (progressBar != null)
                    progressBar.value = Mathf.Clamp01(loading.progress / 0.9f);

                yield return null;
            }
        }
    }
}
