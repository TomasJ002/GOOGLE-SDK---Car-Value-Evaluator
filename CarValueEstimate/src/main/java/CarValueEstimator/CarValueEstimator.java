package CarValueEstimator;

import com.google.adk.agents.LlmAgent;
import com.google.adk.events.Event;
import com.google.adk.runner.InMemoryRunner;
import com.google.adk.sessions.Session;

import com.google.adk.tools.Annotations.Schema;
import com.google.adk.tools.FunctionTool;
import com.google.adk.tools.GoogleSearchTool;
import com.google.adk.tools.ToolContext;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import io.reactivex.rxjava3.core.Flowable;
import io.github.cdimascio.dotenv.Dotenv;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Scanner;



public class CarValueEstimator {

    private static final String USER_ID = "host";
    private static final String APP_NAME = "car_value_estimator_app";

    public static Map<String, Object> calculateKilometerMultiplier(
            @Schema(name = "kilometer_grading", description = "Sets a multiplier based on the kilometers of a car")
            int kilometers,
            int ageInYears,

            @Schema(name = "toolContext")
            ToolContext toolContext) {
        Map<String, Object> response_km = new HashMap<>();
        int expectedKm = ageInYears * 15000;
        double difference = kilometers - expectedKm;
        double adjustForKm = -(difference / 10000) * 0.05;
        //Point cap
        adjustForKm = Math.max(-0.3, Math.min(0.2, adjustForKm));
        response_km.put("kilometer multiplier",adjustForKm);
        return response_km;
    }
    public static Map<String, Object> calculateAgeMultiplier(
            @Schema(name = "age_grading", description = "Sets a multiplier based on the age of a car")
            int carAge,
            @Schema(name = "toolContext")
            ToolContext toolContext) {
        Map<String, Object> response_age = new HashMap<>();
        double ageMultiplier = 0.0;
        if (carAge <= 1.0 || carAge >= 0.3) {
            ageMultiplier = -0.20;
        } else if (carAge <= 5) {
            ageMultiplier = -0.20 - ((carAge - 1) * 0.08);
        } else if (carAge <= 10) {
            ageMultiplier = -0.52 - ((carAge - 5) * 0.04);
        } else {
            ageMultiplier = -0.75;
        }
        response_age.put("age multiplier", ageMultiplier);
        return response_age;
    }
    public static Map<String, Object> calculateConditionMultiplier(
            @Schema(name = "condition_grading", description = "Sets a multiplier based on the condition of the car")
            int condition,
            @Schema(name = "toolContext")
            ToolContext toolContext){
        Map<String,Object> response_condition = new HashMap<>();
        double conditionMultiplier = 0.0;
        conditionMultiplier = switch (condition) {
            case 1 -> 0.10;
            case 2 -> 0.0;
            case 3 -> -0.15;
            case 4 -> -0.30;
            case 5 -> -0.60;
            default -> 0.0;
        };
        response_condition.put("condition multiplier", conditionMultiplier);
        return response_condition;
    }

        public static void main(String[] args) throws Exception {
        Dotenv dotenv= Dotenv.configure()
                .load();

        LlmAgent priceNew = LlmAgent.builder()
                .model("gemini-flash-latest")
                .name("Price when new agent")
                .description("Checks for a price of the car as brand new")
                .instruction("""
                        You are a search agent who researches prices of cars when brand new.
                        Your search depends on the user input.
                        Your rules:
                        1. Always search for the new price of said car in the country, in which the car is currently being advertised in.
                        2. Discount all special offers and bonuses from the month of the vehicle registration.
                        3. If the price of the current country of listing is different than Euro, use the latest currency exchange rate""")
                .tools(new GoogleSearchTool())
                .build();

        LlmAgent specChecker = LlmAgent.builder()
                .model("gemini-flash-latest")
                .name("spec checker")
                .description("checks and ranks the spec of the car")
                .instruction("""
                        You are a professional car analyst designed to analyze a car's specifications and assign a value multiplier score.
                        You must output strictly in JSON format matching this schema:
                        {
                            "points_spec": float,
                            "warnings": [array of strings]
                        }
                        Task 1: Trim Tiering
                            Research the full trim lineup for this specific Year, Make, and Model. Map the user's trim to one of the following 5 tiers and apply the corresponding score:
                            - Tier 1 (Entry-Level / Barebones - e.g., LX, Base): -0.10
                            - Tier 2 (Volume / Lower-Mid - e.g., LE, Sport): -0.05
                            - Tier 3 (Mid-Range / Well-Equipped - e.g., EX, XLE): 0.00
                            - Tier 4 (Premium / Upper-Mid - e.g., EX-L, Limited): +0.08
                            - Tier 5 (Top-Tier / Flagship / Performance - e.g., Touring, Platinum, Type-R): +0.15
                        Task 2: Extra Options
                            Identify any standalone major extra options the user has that are NOT standard on their specific trim.
                            Add +0.03 for each major extra luxury or tech option (cap this at a maximum of +0.09).
                        
                        Task 3: Red Flags
                        If the car has a tow hinge, aftermarket sunroof, or air suspension, subtract -0.05 for each.\s
                        AND add a clear string to the "warnings" array explaining that this specific component is prone to failure on used models and reduces the vehicle's long-term reliability score.
                        """)
                .tools(new GoogleSearchTool())
                .build();

        LlmAgent orchestrator = LlmAgent.builder()
                .model("gemini-1.5-pro")
                .name("Car value estimator")
                .description("The main agent for car evaluation")
                .instruction("""
                        You are a professional Lead Car Appraiser.
                        Your goal is to appraise the user's vehicle by coordinating your sub-agents and tools.
                        
                        ### YOUR WORKFLOW:
                        1. **Data Intake:** Collect Year, Make, Model, Trim, exact Kilometers, Condition (1-5 scale), and major extra options. Ask clarifying questions if any are missing.
                        2. **Evaluation:**
                            - Call the 'Price when new agent' sub-agent to find the base MSRP of the car when new.
                            - Call the 'spec checker' sub-agent to evaluate the trim and extra options.
                            - Call 'calculateAgeMultiplier' with the car's age.
                            - Call 'calculateKilometerMultiplier' with the kilometers and age.
                            - Call 'calculateConditionMultiplier' with the condition integer (1-5).
                        3. **Synthesis & Explanation:**\s
                            - Sum all the multiplier points returned by your tools and sub-agents, starting with a baseline score of 1.0.\s
                            - Multiply the base MSRP by this final multiplier to get the estimated value.
                            - Present the final valuation to the user, breaking down exactly how you arrived at the price using the text explanations provided by your tools. NEVER calculate the point deductions yourself.
                        """)
                .subAgents(priceNew, specChecker)
                .tools(
                        FunctionTool.create(CarValueEstimator.class, "calculateKilometerMultiplier"),
                        FunctionTool.create(CarValueEstimator.class, "calculateAgeMultiplier"),
                        FunctionTool.create(CarValueEstimator.class, "calculateConditionMultiplier")
                )
                .build();


        InMemoryRunner runner = new InMemoryRunner(orchestrator);

    Session session =
            runner
                    .sessionService()
                    .createSession(APP_NAME, USER_ID)
                    .blockingGet();

        try (Scanner scanner = new Scanner(System.in, StandardCharsets.UTF_8)){
        while (true) {
            System.out.print("\nYou > ");
            String userInput = scanner.nextLine();

            if ("quit".equalsIgnoreCase(userInput)) {
                break;
            }

            Content userMsg = Content.fromParts(Part.fromText(userInput));
            Flowable<Event> events = runner.runAsync(USER_ID, session.id(), userMsg);

            System.out.print("\nAgent > ");
            events.blockingForEach(event -> System.out.println(event.stringifyContent()));
            }
        }
    }
}