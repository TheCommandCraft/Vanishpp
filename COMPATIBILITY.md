# Compatibility Matrix - Vanishpp

## Supported Versions
Vanishpp is built and tested against the **1.21** API.

| Minecraft Version | Status | Notes |
| :--- | :--- | :--- |
| **1.21.x** | ✅ Supported | Native support. Recommended. |
| **1.20.6** | ⚠️ Likely Compatible | Built with cross-version compatibility in mind, but 1.21 features (like new items) won't work. |
| **1.20.4 and older** | ❌ Unsupported | API changes in 1.20.5+ (ItemStacks/NMS) make this version incompatible. Use Vanishpp 1.0.x. |

## Supported Platforms
| Platform | Status | Notes |
| :--- | :--- | :--- |
| **Paper** | ✅ Recommended | Best performance. Required for full API support (Projectile events). |
| **Spigot** | ⚠️ Compatible | Works, but some advanced physics features (arrow passthrough) may degrade. |
| **Purpur** | ✅ Supported | Fully compatible (Fork of Paper). |
| **Folia** | ❓ Untested | Threading model *may* cause issues with current schedulers. Not officially supported yet. |

## Java Requirement
- **Java 21** is required for this version.
