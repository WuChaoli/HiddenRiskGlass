# Android 功能模块开发模板

## 模块结构

```
feature-{module-name}/
├── api/                          # 导航接口（可选，多模块项目）
│   └── src/main/kotlin/.../
│       └── {ModuleName}Navigation.kt
├── impl/                         # 实现
│   └── src/main/kotlin/.../
│       ├── navigation/
│       │   └── {ModuleName}Navigation.kt
│       ├── {module-name}/
│       │   ├── data/
│       │   │   ├── model/
│       │   │   │   ├── {Module}Entity.kt        # Room 实体
│       │   │   │   ├── {Module}Network.kt       # API 响应模型
│       │   │   │   └── {Module}.kt              # Domain 模型
│       │   │   ├── mapper/
│       │   │   │   └── {Module}Mapper.kt        # 模型转换
│       │   │   ├── datasource/
│       │   │   │   ├── {Module}LocalDataSource.kt
│       │   │   │   └── {Module}RemoteDataSource.kt
│       │   │   └── repository/
│       │   │       └── {Module}Repository.kt
│       │   ├── di/
│       │   │   └── {Module}Module.kt            # Hilt 模块
│       │   └── ui/
│       │       ├── {Module}ViewModel.kt
│       │       ├── {Module}Screen.kt            # Route 层
│       │       ├── {Module}Content.kt           # Screen 层
│       │       └── components/                  # Component 层
│       │           └── {Module}Item.kt
│       └── ...
└── build.gradle.kts
```

## 开发顺序

1. **Domain Model** - 定义业务数据模型
2. **Network/Entity Model** - 定义 API 响应和数据库实体
3. **Model Mapper** - 实现模型转换
4. **DataSource** - 实现数据源（Remote + Local）
5. **Repository** - 实现仓库（离线优先）
6. **Hilt Module** - 配置依赖注入
7. **ViewModel** - 实现业务逻辑和状态管理
8. **UI Components** - 实现可复用组件
9. **Screen** - 实现页面
10. **Navigation** - 配置导航
11. **Tests** - 编写测试

## 文件模板

### Domain Model
```kotlin
// 纯 Kotlin，零 Android 依赖
data class {Module}(
    val id: String,
    val name: String,
    // ...
)
```

### UiState
```kotlin
sealed interface {Module}UiState {
    data object Loading : {Module}UiState
    data class Success(
        val items: List<{Module}>
    ) : {Module}UiState
    data class Error(
        val message: String
    ) : {Module}UiState
}
```

### ViewModel
```kotlin
@HiltViewModel
class {Module}ViewModel @Inject constructor(
    private val repository: {Module}Repository
) : ViewModel() {
    
    private val _uiState = MutableStateFlow<{Module}UiState>({Module}UiState.Loading)
    val uiState: StateFlow<{Module}UiState> = _uiState
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = {Module}UiState.Loading
        )
    
    fun onEvent(event: {Module}Event) {
        when (event) {
            is {Module}Event.LoadData -> loadData()
        }
    }
    
    private fun loadData() {
        viewModelScope.launch {
            repository.get{Module}s()
                .catch { e -> _uiState.value = {Module}UiState.Error(e.message ?: "Unknown error") }
                .collect { items -> _uiState.value = {Module}UiState.Success(items) }
        }
    }
}
```

### Screen (Route 层)
```kotlin
@Composable
fun {Module}Route(
    viewModel: {Module}ViewModel = hiltViewModel(),
    onBack: () -> Unit,
    onNavigateToDetail: (String) -> Unit
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    {Module}Screen(
        uiState = uiState,
        onBack = onBack,
        onNavigateToDetail = onNavigateToDetail,
        onEvent = viewModel::onEvent
    )
}
```

### Screen (Screen 层 - 无状态)
```kotlin
@Composable
fun {Module}Screen(
    uiState: {Module}UiState,
    onBack: () -> Unit,
    onNavigateToDetail: (String) -> Unit,
    onEvent: ({Module}Event) -> Unit
) {
    when (uiState) {
        is {Module}UiState.Loading -> LoadingContent()
        is {Module}UiState.Success -> {Module}Content(
            items = uiState.items,
            onNavigateToDetail = onNavigateToDetail
        )
        is {Module}UiState.Error -> ErrorContent(
            message = uiState.message,
            onRetry = { onEvent({Module}Event.LoadData) }
        )
    }
}
```

### Hilt Module
```kotlin
@Module
@InstallIn(SingletonComponent::class)
object {Module}Module {
    
    @Provides
    @Singleton
    fun provide{Module}Repository(
        localDataSource: {Module}LocalDataSource,
        remoteDataSource: {Module}RemoteDataSource
    ): {Module}Repository = {Module}RepositoryImpl(
        localDataSource = localDataSource,
        remoteDataSource = remoteDataSource
    )
}
```
