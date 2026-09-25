using System.Threading;
using Cysharp.Threading.Tasks;

namespace StbEmu.Profiles
{
    public interface IProfileRepository
    {
        UniTask<ProfileDocument> LoadAsync(CancellationToken ct);
        UniTask SaveAsync(ProfileDocument document, CancellationToken ct);
    }
}
