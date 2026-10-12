using System;
using System.Collections.Generic;
using System.Threading;
using Cysharp.Threading.Tasks;

namespace StbEmu.Profiles
{
    public sealed class ProfileValidationException : Exception
    {
        public readonly IReadOnlyList<string> Errors;

        public ProfileValidationException(List<string> errors) : base(string.Join(" ", errors))
        {
            Errors = errors;
        }
    }

    /// <summary>
    /// In-memory profile list backed by a repository. Every mutation is validated, saved, and rolled back if the save fails.
    /// Profiles returned by <see cref="Profiles"/> are live objects: edit a copy and pass it to <see cref="UpdateAsync"/>.
    /// </summary>
    public sealed class ProfileManager
    {
        readonly IProfileRepository repository;
        ProfileDocument document = new ProfileDocument();
        bool busy; // ponytail: one mutation at a time (UI-driven); queue with SemaphoreSlim if background callers appear

        public ProfileManager(IProfileRepository repository)
        {
            this.repository = repository;
        }

        public IReadOnlyList<Profile> Profiles => document.Profiles;

        public Profile Find(string profileId) => document.Profiles.Find(p => p.ProfileId == profileId);

        public async UniTask LoadAsync(CancellationToken ct)
        {
            document = await repository.LoadAsync(ct);
        }

        /// <summary>Assigns ProfileId; normalizes the server URL.</summary>
        public async UniTask<Profile> AddAsync(Profile profile, CancellationToken ct)
        {
            if (string.IsNullOrEmpty(profile.ProfileId)) profile.ProfileId = Guid.NewGuid().ToString();
            profile.ServerUrl = ServerAddress.Normalize(profile.ServerUrl);

            if (Find(profile.ProfileId) != null) throw new InvalidOperationException($"Profile {profile.ProfileId} already exists.");
            ThrowIfInvalid(profile, document.Profiles);

            await MutateAsync(() => document.Profiles.Add(profile), () => document.Profiles.Remove(profile), ct);
            return profile;
        }

        /// <summary>Replaces the stored profile with the same ProfileId.</summary>
        public async UniTask UpdateAsync(Profile profile, CancellationToken ct)
        {
            var index = document.Profiles.FindIndex(p => p.ProfileId == profile.ProfileId);
            if (index < 0) throw new KeyNotFoundException($"Profile {profile.ProfileId} not found.");

            profile.ServerUrl = ServerAddress.Normalize(profile.ServerUrl);
            ThrowIfInvalid(profile, document.Profiles.FindAll(p => p.ProfileId != profile.ProfileId));

            var previous = document.Profiles[index];
            await MutateAsync(() => document.Profiles[index] = profile, () => document.Profiles[index] = previous, ct);
        }

        public async UniTask RemoveAsync(string profileId, CancellationToken ct)
        {
            var index = document.Profiles.FindIndex(p => p.ProfileId == profileId);
            if (index < 0) return;

            var removed = document.Profiles[index];
            await MutateAsync(() => document.Profiles.RemoveAt(index), () => document.Profiles.Insert(index, removed), ct);
        }

        async UniTask MutateAsync(Action apply, Action rollback, CancellationToken ct)
        {
            if (busy) throw new InvalidOperationException("Another profile change is still being saved.");
            busy = true;
            apply();
            try
            {
                await repository.SaveAsync(document, ct);
            }
            catch
            {
                rollback();
                throw;
            }
            finally
            {
                busy = false;
            }
        }

        static void ThrowIfInvalid(Profile profile, IEnumerable<Profile> others)
        {
            var errors = ProfileValidator.Validate(profile, others);
            if (errors.Count > 0) throw new ProfileValidationException(errors);
        }
    }
}
