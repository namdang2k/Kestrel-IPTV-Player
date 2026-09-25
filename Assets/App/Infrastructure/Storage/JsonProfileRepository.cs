using System;
using System.Collections.Generic;
using System.IO;
using System.Threading;
using Cysharp.Threading.Tasks;
using Newtonsoft.Json;
using Newtonsoft.Json.Converters;
using Newtonsoft.Json.Serialization;
using StbEmu.Profiles;

namespace StbEmu.Storage
{
    public sealed class ProfileStoreException : Exception
    {
        public ProfileStoreException(string message, Exception inner = null) : base(message, inner) { }
    }

    /// <summary>
    /// profiles.json in the given directory (Application.persistentDataPath at runtime).
    /// Save = write .tmp, then File.Replace keeps the previous file as .bak. Load falls back to .bak if the main file is unreadable.
    /// </summary>
    public sealed class JsonProfileRepository : IProfileRepository
    {
        static readonly JsonSerializerSettings Settings = new JsonSerializerSettings
        {
            TypeNameHandling = TypeNameHandling.None, // imported JSON must never pick .NET types
            ContractResolver = new CamelCasePropertyNamesContractResolver(),
            Converters = { new StringEnumConverter() },
            Formatting = Formatting.Indented,
        };

        readonly string path;
        readonly string backupPath;
        readonly string tempPath;

        public JsonProfileRepository(string directory)
        {
            path = Path.Combine(directory, "profiles.json");
            backupPath = path + ".bak";
            tempPath = path + ".tmp";
        }

        public async UniTask<ProfileDocument> LoadAsync(CancellationToken ct)
        {
            if (!File.Exists(path) && !File.Exists(backupPath)) return new ProfileDocument();

            Exception mainError;
            try
            {
                return await ReadAsync(path, ct);
            }
            catch (Exception e) when (e is JsonException || e is IOException) // schema-too-new propagates: never fall back and lose newer data
            {
                mainError = e;
            }

            try
            {
                var document = await ReadAsync(backupPath, ct);
                UnityEngine.Debug.LogWarning($"profiles.json unreadable ({mainError.Message}); restored from backup.");
                return document;
            }
            catch (Exception e) when (e is JsonException || e is IOException)
            {
                throw new ProfileStoreException("Profiles file and its backup are both unreadable.", mainError);
            }
        }

        public async UniTask SaveAsync(ProfileDocument document, CancellationToken ct)
        {
            document.SchemaVersion = ProfileDocument.CurrentSchemaVersion;
            await File.WriteAllTextAsync(tempPath, JsonConvert.SerializeObject(document, Settings), ct);

            if (File.Exists(path)) File.Replace(tempPath, path, backupPath);
            else File.Move(tempPath, path);
        }

        static async UniTask<ProfileDocument> ReadAsync(string file, CancellationToken ct)
        {
            if (!File.Exists(file)) throw new FileNotFoundException("Missing", file);

            var document = JsonConvert.DeserializeObject<ProfileDocument>(await File.ReadAllTextAsync(file, ct), Settings)
                ?? throw new JsonSerializationException("Empty profiles file.");

            if (document.SchemaVersion > ProfileDocument.CurrentSchemaVersion)
                throw new ProfileStoreException($"Profiles file has schema {document.SchemaVersion}; this app supports {ProfileDocument.CurrentSchemaVersion}. Update the app.");

            document.Profiles ??= new List<Profile>();
            return document;
        }
    }
}
